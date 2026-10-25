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

Testes: `./mvnw test` (também rodam no CI, job `redis-api-tests`).

## Endpoints

| Método e rota | Uso |
|---|---|
| `GET /health` | `{status, redis}`: a API está no ar e fala com o Redis? |
| `GET /api/ranking?category=&limit=` | Top N do ano corrente. Com `Authorization: Bearer <accessToken da Core>`, inclui `myPosition` |
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

| Situação | Status |
|---|---|
| `category` inválida ou `limit` fora da faixa | 400 |
| Bearer recusado pela Core / rebuild sem `X-Service-Token` | 401 |
| Outro rebuild em andamento | 409 |
| Core fora do ar (só quando há Bearer) | 503 |
| Nenhum rebuild publicado ainda | 503 |
| Postgres fora do ar no rebuild (o ranking anterior continua no ar) | 503 |

## Rebuild

1. Pega a trava `ranking:rebuild:lock` (`SET NX` com TTL); um segundo rebuild recebe 409.
2. Lê **tudo** de `vw_annual_scores` (com `users.name` e, no B2B, `addresses.city`) antes de tocar no Redis.
3. Calcula as posições densas.
4. Grava em chaves `:tmp` e troca as chaves visíveis e a meta num único `MULTI/EXEC` com `RENAME`.

Se o Postgres falhar, nada no Redis muda. Roda sozinho a cada `OLIUS_REBUILD_INTERVAL`
(padrão `5m`) quando `OLIUS_DATABASE_URL` está definida.

## Modelagem no Redis

| Chave | Tipo | Uso |
|---|---|---|
| `ranking:{establishment\|citizen}:{year}` | ZSET | member = `users.id`, score = `annual_points` |
| `ranking:{establishment\|citizen}:{year}:positions` | HASH | `users.id` → posição densa |
| `profile:{establishment\|citizen}:{id}` | HASH | `name` e `city` (só B2B) |
| `ranking:meta` | HASH | `year` e `updatedAt` da versão visível |
| `ranking:rebuild:lock` | STRING | trava do rebuild |

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

## Publicação

A imagem do `Dockerfile` roda sem root, usa a variável `PORT` da hospedagem e
responde em `GET /health`. Variáveis de produção:

| Variável | Valor em produção |
|---|---|
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Dados TCP do Upstash |
| `REDIS_SSL` | `true` |
| `OLIUS_DATABASE_URL` | `jdbc:postgresql://<host>:<porta>/<banco>?sslmode=require` |
| `OLIUS_DATABASE_USER`, `OLIUS_DATABASE_PASSWORD` | Conta criada pelo time de banco |
| `OLIUS_REBUILD_INTERVAL` | `5m` (ou o intervalo combinado) |
| `OLIUS_CORE_BASE_URL` | URL HTTPS pública da Core |
| `OLIUS_SERVICE_TOKEN` | Segredo longo (`openssl rand -hex 32`) |
| `OLIUS_SEED` | `false` |
| `OLIUS_API_DOCS_ENABLED` | `false` (Swagger privado; é o padrão) |
| `OLIUS_ALLOWED_ORIGINS` | Vazio para o app Android |

Ao subir, a API faz o primeiro rebuild em segundos. Até lá, `GET /api/ranking` responde 503.
