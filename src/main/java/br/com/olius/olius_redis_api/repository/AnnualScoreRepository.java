package br.com.olius.olius_redis_api.repository;

import br.com.olius.olius_redis_api.config.OliusProperties;
import br.com.olius.olius_redis_api.exception.DatabaseUnavailableException;
import br.com.olius.olius_redis_api.model.AnnualScore;
import br.com.olius.olius_redis_api.model.RankingCategory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Reads the official annual scores from {@code public.vw_annual_scores}.
 * The view only returns the participant id, so name and (B2B only) city are
 * joined from users, establishment and addresses. Points are never recalculated here.
 */
@Repository
public class AnnualScoreRepository {

    static final String SQL = """
            SELECT s.profile,
                   s.ranking_year,
                   s.participant_id::text AS participant_id,
                   s.annual_points,
                   u.name,
                   a.city
              FROM public.vw_annual_scores s
              JOIN public.users u              ON u.id = s.participant_id
              LEFT JOIN public.establishment e ON e.id = s.participant_id AND s.profile = 'B2B'
              LEFT JOIN public.addresses a     ON a.id = e.address_id
             WHERE u.status = 'ACTIVE'
             ORDER BY s.profile, s.annual_points DESC, s.participant_id
            """;

    private static final int TIMEOUT_SECONDS = 30;

    private final OliusProperties properties;

    public AnnualScoreRepository(OliusProperties properties) {
        this.properties = properties;
    }

    public boolean configured() {
        return properties.databaseUrl() != null && !properties.databaseUrl().isBlank();
    }

    /** Single read-only query, so both categories come from the same snapshot. */
    public List<AnnualScore> findAll() {
        if (!configured()) {
            throw new DatabaseUnavailableException("Set OLIUS_DATABASE_URL to enable the rebuild.");
        }
        try (Connection conn = DriverManager.getConnection(
                properties.databaseUrl(), properties.databaseUser(), properties.databasePassword())) {
            conn.setReadOnly(true);
            try (PreparedStatement st = conn.prepareStatement(SQL)) {
                st.setQueryTimeout(TIMEOUT_SECONDS);
                try (ResultSet rs = st.executeQuery()) {
                    List<AnnualScore> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new AnnualScore(
                                RankingCategory.fromViewProfile(rs.getString("profile")),
                                rs.getInt("ranking_year"),
                                rs.getString("participant_id"),
                                rs.getBigDecimal("annual_points").longValueExact(),
                                rs.getString("name"),
                                rs.getString("city")));
                    }
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new DatabaseUnavailableException("Could not read PostgreSQL (SQLState " + e.getSQLState()
                    + "). The last published ranking was kept.");
        }
    }
}
