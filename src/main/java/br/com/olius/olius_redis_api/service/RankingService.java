package br.com.olius.olius_redis_api.service;

import br.com.olius.olius_redis_api.dto.RankingDtos.MyPosition;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingItem;
import br.com.olius.olius_redis_api.dto.RankingDtos.RankingResponse;
import br.com.olius.olius_redis_api.exception.RankingUnavailableException;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.model.UserSession;
import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

/** Annual ranking by points, per category, with dense positions on ties. */
@Service
public class RankingService {

    /** Display order: most points first, ties by id. */
    private static final Comparator<RankedParticipant> ORDER = Comparator
            .comparingLong(RankedParticipant::points).reversed()
            .thenComparing(RankedParticipant::id);

    private final RankingRedisRepository repository;

    public RankingService(RankingRedisRepository repository) {
        this.repository = repository;
    }

    /** Sorts and assigns dense positions (1, 1, 2), same as DENSE_RANK() in vw_annual_ranking. */
    public static List<RankedParticipant> rank(List<AnnualScore> scores) {
        List<RankedParticipant> sorted = new ArrayList<>(scores.size());
        for (AnnualScore s : scores) {
            sorted.add(new RankedParticipant(s.participantId(), s.points(), 0, s.name(), s.city()));
        }
        sorted.sort(ORDER);

        List<RankedParticipant> ranked = new ArrayList<>(sorted.size());
        int position = 0;
        Long previous = null;
        for (RankedParticipant p : sorted) {
            if (previous == null || p.points() != previous) {
                position++;
                previous = p.points();
            }
            ranked.add(new RankedParticipant(p.id(), p.points(), position, p.name(), p.city()));
        }
        return ranked;
    }

    /**
     * Top N in display order. Redis breaks ties by reverse member order, so
     * when the cut falls inside a tie all tied members are fetched and the
     * lowest ids are kept.
     */
    static List<TypedTuple<String>> orderTop(Set<TypedTuple<String>> top, Set<TypedTuple<String>> tiedAtCut,
                                            int limit) {
        List<TypedTuple<String>> list = new ArrayList<>(top);
        if (!list.isEmpty() && list.size() == limit) {
            double cut = score(list.get(list.size() - 1));
            list.removeIf(t -> score(t) == cut);
            list.addAll(tiedAtCut);
        }
        list.sort(Comparator.<TypedTuple<String>>comparingDouble(RankingService::score).reversed()
                .thenComparing(TypedTuple::getValue));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private static double score(TypedTuple<String> tuple) {
        return tuple.getScore() == null ? 0 : tuple.getScore();
    }

    /** Builds the ranking page; {@code myPosition} only when the session matches the category. */
    public RankingResponse build(RankingCategory category, int limit, UserSession session) {
        Map<String, String> meta = repository.meta();
        if (meta.get("year") == null) {
            throw new RankingUnavailableException();
        }
        int year = Integer.parseInt(meta.get("year"));
        String key = RankingRedisRepository.rankingKey(category, year);
        String positionsKey = RankingRedisRepository.positionsKey(category, year);

        Set<TypedTuple<String>> top = repository.top(key, limit);
        Set<TypedTuple<String>> tied = top.size() == limit
                ? repository.withScore(key, top.stream().mapToDouble(RankingService::score).min().orElse(0))
                : Set.of();
        List<TypedTuple<String>> ordered = orderTop(top, tied, limit);

        List<String> ids = ordered.stream().map(TypedTuple::getValue).toList();
        List<Map<String, String>> profiles = repository.profiles(category, ids);
        List<Integer> positions = repository.positions(positionsKey, ids);
        String myId = session != null && session.category() == category ? session.userId() : null;

        List<RankingItem> items = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            String name = profiles.get(i).getOrDefault("name", id);
            String city = profiles.get(i).get("city");
            items.add(new RankingItem(
                    id,
                    positions.get(i) == null ? 0 : positions.get(i),
                    name,
                    city == null || city.isBlank() ? null : city,
                    initials(name),
                    (long) score(ordered.get(i)),
                    id.equals(myId)));
        }

        MyPosition myPosition = null;
        if (myId != null) {
            Integer position = repository.positions(positionsKey, List.of(myId)).get(0);
            Double points = repository.score(key, myId);
            if (position != null && points != null) {
                myPosition = new MyPosition(position, points.longValue());
            }
        }
        return new RankingResponse(year, meta.get("updatedAt"), myPosition, items);
    }

    /** Avatar initials: "Maria Silva" -> "MS", "ToKonFome" -> "TK", otherwise the first two letters. */
    public static String initials(String name) {
        String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "??";
        }
        if (parts.length > 1) {
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
        }
        String word = parts[0];
        for (int i = 1; i < word.length(); i++) {
            if (Character.isUpperCase(word.charAt(i))) {
                return ("" + word.charAt(0) + word.charAt(i)).toUpperCase();
            }
        }
        return word.substring(0, Math.min(2, word.length())).toUpperCase();
    }
}
