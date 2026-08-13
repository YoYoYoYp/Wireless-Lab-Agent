package com.njupt.wirelesslabagent.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 基于标注来源的检索指标；同一来源的重复切片不会重复增加 Recall、AP 或 NDCG。 */
public record RetrievalMetrics(List<String> retrievedSources,
                               Set<String> expectedSources,
                               Set<String> availableSources,
                               int k) {

    public RetrievalMetrics {
        retrievedSources = List.copyOf(retrievedSources);
        expectedSources = Set.copyOf(expectedSources);
        availableSources = Set.copyOf(availableSources);
    }

    public double recall() {
        Set<String> relevant = relevantSources();
        if (relevant.isEmpty()) return 0;
        return (double) retrievedSources.stream().limit(k).filter(relevant::contains).distinct().count()
                / relevant.size();
    }

    public double precision() {
        List<String> topK = retrievedSources.stream().limit(k).toList();
        if (topK.isEmpty()) return 0;
        return (double) topK.stream().filter(relevantSources()::contains).count() / topK.size();
    }

    public double averagePrecision() {
        Set<String> relevant = relevantSources();
        if (relevant.isEmpty()) return 0;
        Set<String> seenRelevantSources = new HashSet<>();
        int uniqueHits = 0;
        double precisionSum = 0;
        List<String> topK = retrievedSources.stream().limit(k).toList();
        for (int i = 0; i < topK.size(); i++) {
            String source = topK.get(i);
            if (relevant.contains(source) && seenRelevantSources.add(source)) {
                uniqueHits++;
                precisionSum += (double) uniqueHits / (i + 1);
            }
        }
        return precisionSum / relevant.size();
    }

    public double ndcg() {
        Set<String> relevant = relevantSources();
        if (relevant.isEmpty()) return 0;
        Set<String> seen = new HashSet<>();
        double dcg = 0;
        List<String> topK = retrievedSources.stream().limit(k).toList();
        for (int i = 0; i < topK.size(); i++) {
            String source = topK.get(i);
            if (relevant.contains(source) && seen.add(source)) {
                dcg += 1.0 / log2(i + 2);
            }
        }

        int idealHits = Math.min(relevant.size(), k);
        double idcg = 0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    public double reciprocalRank() {
        Set<String> relevant = relevantSources();
        List<String> topK = retrievedSources.stream().limit(k).toList();
        for (int i = 0; i < topK.size(); i++) {
            if (relevant.contains(topK.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0;
    }

    public boolean hit() {
        return reciprocalRank() > 0;
    }

    private Set<String> relevantSources() {
        Set<String> relevant = new HashSet<>(expectedSources);
        relevant.retainAll(availableSources);
        return relevant;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
