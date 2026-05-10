package com.patbaumgartner.zalando.lounge.cartpilot.domain.service;

/**
 * Pure function: computes the Levenshtein edit distance between two strings. No framework
 * dependency — testable in complete isolation.
 */
public final class LevenshteinDistance {

	private LevenshteinDistance() {
	}

	/**
	 * Returns the minimum number of single-character edits (insertions, deletions,
	 * substitutions) needed to transform {@code a} into {@code b}.
	 */
	public static int compute(String a, String b) {
		if (a.equals(b)) {
			return 0;
		}

		int m = a.length();
		int n = b.length();

		// Use two rows to save memory — O(n) space instead of O(m*n)
		int[] prev = new int[n + 1];
		int[] curr = new int[n + 1];

		for (int j = 0; j <= n; j++) {
			prev[j] = j;
		}

		for (int i = 1; i <= m; i++) {
			curr[0] = i;
			for (int j = 1; j <= n; j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] swap = prev;
			prev = curr;
			curr = swap;
		}
		return prev[n];
	}

}
