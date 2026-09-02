package com.craftpilot.clientpolicy.policy;

import com.craftpilot.clientpolicy.fingerprint.ClientLoader;
import com.craftpilot.clientpolicy.signature.ModSignature;

import java.util.List;
import java.util.Map;

/**
 * Pure result of evaluating one fingerprint against one profile. Contains no
 * side effects; {@code /clientpolicy inspect} and {@code check} render it directly.
 *
 * @param matched         signature -> the channels (or {@code brand:<value>}) that triggered it
 * @param unknownChannels channels that matched nothing and were not ignored
 */
public record EvaluationResult(ResolvedProfile profile,
                               String brand,
                               ClientLoader loader,
                               Map<ModSignature, List<String>> matched,
                               List<String> unknownChannels,
                               List<String> ignoredChannels,
                               List<Violation> violations) {

    public boolean clean() {
        return violations.isEmpty();
    }
}
