# Olius — API de Ranking (Java + Spring MVC + Redis)

API REST em Java do projeto, com acesso a um banco **NoSQL (Redis)**.

Atende só a aba **Competições** da tela de ranking: ranking **anual por pontos**,
filtrado por estabelecimento ou cidadão. A fonte é `public.vw_annual_scores`
(`PostgreSQL/06_ctes.sql`). O Redis guarda uma projeção reconstruível; o PostgreSQL
continua sendo a fonte da verdade. Nível do usuário e conquistas não ficam nesta API.

```
Core executa a operação → PostgreSQL grava entrega/coleta e pontos
                                   ↓ (rebuild periódico, JDBC somente leitura)
              esta API lê vw_annual_scores → publica o ranking no Redis
                                   ↓
App Android --GET /api/ranking--> esta API --Lettuce--> Redis
             (Bearer da Core)        └──GET /api/v1/auth/session──> Core
```

## Como rodar

**Com Docker (Redis + API):**

```bash
OLIUS_SEED=true OLIUS_API_DOCS_ENABLED=true docker compose up --build   # dados de demonstração
# http://localhost:8081/health
# http://localhost:8081/swagger-ui/index.html
```

**Direto na máquina (precisa de um Redis rodando):**

```bash
cp .env.example .env          # Redis, OLIUS_DATABASE_*, OLIUS_CORE_BASE_URL e OLIUS_SERVICE_TOKEN
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Testes: `./mvnw verify` (Windows: `mvnw.cmd verify`). O Docker precisa estar em execução:
o Testcontainers cria Redis e PostgreSQL descartáveis, sem usar o banco do desenvolvedor.
O workflow `.github/workflows/ci.yml` executa essa suíte no job `redis-api-tests`.
Os testes SQL usam uma fixture reduzida do contrato de leitura; não substituem a suíte
oficial de regras de negócio do repositório PostgreSQL. A validação HTTP da Core utiliza
um servidor simulado, não uma instalação real da Core.

## Endpoints

| Método e rota | Uso |
|---|---|
| `GET /health` | Processo no ar; retorna também o diagnóstico `redis`. Não usar como readiness |
| `GET /health/ready` | Redis acessível: 200; indisponível: 503. Não garante que o primeiro ranking já tenha sido publicado |
| `GET /api/ranking?category=&limit=` | Top N do ano corrente. Exige `Authorization: Bearer <accessToken da Core>`; inclui `myPosition` quando aplicável |
| `POST /api/ranking/rebuild` | Reconstrói o ranking a partir de `vw_annual_scores` (exige `X-Service-Token`) |

`category` = `establishment` \| `citizen` (padrão `establishment`); `limit` de 1 a 100 (padrão 10).

Resposta de `GET /api/ranking`:

```json
{
  "year": 2026,
  "updatedAt": "2026-10-24T10:15:00-03:00",
  "myPosition": { "position": 3, "points": 1250 },
  "items": [
    { "id": "uuid", "position": 1, "name": "ToKonFome", "city": "Itaquaquecetuba",
      "initials": "TK", "points": 1800, "currentUser": false }
  ]
}
```

- `position` é densa: empatados dividem a posição (1, 1, 2). Na lista, os empatados aparecem ordenados por `id`.
- `city` só existe para estabelecimento; cidadão não tem endereço próprio.
- `myPosition` vem do token validado na Core, nunca da query string. Só aparece quando o tipo do usuário corresponde à `category` pedida.
- `updatedAt` é o último rebuild íntegro: o ranking não é instantâneo.
- Toda consulta do ranking exige sessão ativa na Core. No Swagger, use **Authorize → coreBearer**.
- O token técnico `serviceToken` serve somente para reconstruir o ranking, não para autenticar usuários.

| Situação | Status |
|---|---|
| `category` inválida ou `limit` fora da faixa | 400 |
| Bearer ausente, inválido ou recusado pela Core / rebuild sem `X-Service-Token` | 401 |
| Outro rebuild em andamento | 409 |
| Core fora do ar ou resposta de sessão inválida | 503 |
| Cache ausente, incompleto ou de outro ano, sem recuperação bem-sucedida | 503 |
| Postgres fora do ar no rebuild (o ranking anterior continua no ar) | 503 |

## Rebuild

1. Pega a trava `{olius-ranking}:lock` (`SET NX` com TTL de cinco minutos); um segundo rebuild recebe 409.
2. Lê **tudo** de `vw_annual_scores` (com `users.name` e, no B2B, `addresses.city`) antes de tocar no Redis.
3. Calcula as posições densas.
4. Grava pontos, posições e perfis numa geração exclusiva, inicialmente com expiração de dez minutos.
5. Um script Lua confere a propriedade da trava e a existência das chaves antes de trocar o identificador da geração visível. Se a trava expirou, a publicação é recusada.
6. Cada consulta lê metadados, pontos, posições e perfis em um único script Lua. Não mistura gerações durante uma reconstrução.

A geração ativa não expira. Ao ser substituída, suas chaves recebem validade de dez
minutos para limpeza automática. Gerações preparadas mas não publicadas também expiram.
O desempate visual é pelo ID crescente e a posição permanece densa. Pontuações fora
da faixa inteira exata suportada pelo Sorted Set são recusadas, preservando a versão anterior.

Se o Postgres falhar, nada no Redis muda. Roda sozinho a cada `OLIUS_REBUILD_INTERVAL`
(padrão `5m`) quando `OLIUS_DATABASE_URL` está definida e `OLIUS_REBUILD_ENABLED=true`.
Essa API usa credenciais próprias, com SELECT em `vw_annual_scores` e nas colunas
`users(id,name,status)`, `establishment(id,address_id)` e `addresses(id,city)`.
O modo somente leitura do JDBC não substitui as permissões concedidas pelo PostgreSQL.

**Recuperação sob demanda:** se uma consulta autenticada encontrar cache ausente,
incompleto ou de outro ano, tenta executar o mesmo rebuild do PostgreSQL. Uma chave
no Redis limita essas tentativas a uma a cada 30 segundos, compartilhada entre as
instâncias. A trava de reconstrução continua obrigatória. Se a recuperação funcionar,
a consulta já retorna a nova versão. Se falhar, estiver em andamento ou permanecer
sem dados íntegros do ano atual, responde 503. Categorias legitimamente vazias são
respostas válidas e não disparam reconstruções repetidas. Se o próprio Redis cair,
responde 503 sem consultar o PostgreSQL, pois não há onde publicar o resultado.
O ano utiliza o relógio da aplicação em `America/Sao_Paulo`; mantenha os relógios do
servidor e do PostgreSQL sincronizados. O último cache é preservado durante falhas,
mas não é apresentado como ranking de um ano diferente.

`OLIUS_SEED=true` e `OLIUS_DATABASE_URL` preenchida são incompatíveis: a aplicação
recusa essa configuração. `OLIUS_SEED_CLEAR=true` exige modo de demonstração e a
limpeza também participa da trava. Não habilitar demonstração em ambiente real.

**Atualização de uma instalação anterior:** o namespace de cache mudou. O primeiro
rebuild deve publicar a nova geração; as chaves antigas não serão consultadas. Não é
necessária migração no PostgreSQL. As versões antiga e nova da API não devem operar
simultaneamente sobre o mesmo cache durante a troca, pois usam travas diferentes.

## Modelagem no Redis

| Chave | Tipo | Uso |
|---|---|---|
| `{olius-ranking}:generation:{year}:{uuid}:{category}:scores` | ZSET | `users.id` → pontos anuais |
| `{olius-ranking}:generation:{year}:{uuid}:{category}:data` | HASH | posições, pontos inteiros, nomes e cidade por participante; contagem para validar integridade e categoria vazia |
| `{olius-ranking}:meta` | HASH | geração visível, ano e `updatedAt` |
| `{olius-ranking}:lock` | STRING | proprietário da reconstrução |
| `{olius-ranking}:recovery` | STRING | intervalo mínimo entre tentativas sob demanda |

## Organização (padrão MVC)

| Pacote | Responsabilidade |
|---|---|
| `controller` | Rotas HTTP (Spring MVC) e validação dos parâmetros |
| `service` | Ranking, rebuild, validação de sessão na Core e seed de demonstração |
| `repository` | Redis (`StringRedisTemplate`) e leitura de `vw_annual_scores` (JDBC) |
| `dto` | Records de resposta |
| `model` | `RankingCategory`, `AnnualScore`, `RankedParticipant`, `UserSession` |
| `exception` | `GlobalExceptionHandler` (Problem Details) |
| `config` | Propriedades, CORS, token de serviço, rebuild agendado, relógio e OpenAPI |
