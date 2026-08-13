package com.njupt.wirelesslabagent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    @Test
    void duplicateChunksMustNotInflateSourceRecallOrAveragePrecision() {
        RetrievalMetrics metrics = new RetrievalMetrics(
                List.of("source-a", "source-a", "noise"),
                Set.of("source-a", "source-b"),
                Set.of("source-a", "source-b", "noise"),
                3);

        assertEquals(0.5, metrics.recall());
        assertEquals(0.5, metrics.averagePrecision());
        assertTrue(metrics.ndcg() < 1.0);
    }

    @Test
    void idealRankingShouldReachOne() {
        RetrievalMetrics metrics = new RetrievalMetrics(
                List.of("source-a", "source-b", "noise"),
                Set.of("source-a", "source-b"),
                Set.of("source-a", "source-b", "noise"),
                3);

        assertEquals(1.0, metrics.recall());
        assertEquals(1.0, metrics.averagePrecision());
        assertEquals(1.0, metrics.ndcg());
        assertEquals(1.0, metrics.reciprocalRank());
    }
}
