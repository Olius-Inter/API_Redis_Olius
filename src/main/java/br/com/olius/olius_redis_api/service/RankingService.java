package br.com.olius.olius_redis_api.service;

import br.com.olius.olius_redis_api.dto.RankingDtos.RankingResponse;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankedParticipant;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.model.UserSession;
import br.com.olius.olius_redis_api.exception.RankingUnavailableException;
import br.com.olius.olius_redis_api.exception.RebuildInProgressException;
import br.com.olius.olius_redis_api.repository.RankingRedisRepository;
import java.util.ArrayList;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Annual ranking by points, per category, with dense positions on ties. */
@Service
public class RankingService {

    /** Display order: most points first, ties by id. */
    private static final Comparator<RankedParticipant> ORDER = Comparator
            .comparingLong(RankedParticipant::points).reversed()
            .thenComparing(RankedParticipant::id);

    private final RankingRedisRepository repository;
    private final RebuildService rebuildService;
    private final Clock clock;

    public RankingService(RankingRedisRepository repository, RebuildService rebuildService, Clock clock) {
        this.repository = repository;
        this.rebuildService = rebuildService;
        this.clock = clock;
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

    /** Builds the ranking page; {@code myPosition} only when the session matches the category. */
    public RankingResponse build(RankingCategory category, int limit, UserSession session) {
        String myId = session != null && session.category() == category ? session.userId() : null;
        try {
            return readCurrentYear(category, limit, myId);
        } catch (RankingUnavailableException e) {
            if (!rebuildService.databaseConfigured()) {
                throw e;
            }
        }
        if (!repository.claimRecoveryAttempt()) {
            // A parallel rebuild may have finished after our first read.
            return readCurrentYear(category, limit, myId);
        }
        try {
            rebuildService.rebuild();
        } catch (RebuildInProgressException e) {
            throw new RankingUnavailableException();
        }
        return readCurrentYear(category, limit, myId);
    }

    private RankingResponse readCurrentYear(RankingCategory category, int limit, String myId) {
        RankingResponse page = repository.read(category, limit, myId);
        if (page.year() != LocalDate.now(clock).getYear()) {
            throw new RankingUnavailableException();
        }
        return page;
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
