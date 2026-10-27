package org.btsn.handlers;

import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.btsn.utils.XPathHelperCommon;

/**
 * Keeps the token numbers waiting at a place unique.
 *
 * A fork after a join hands back the numbers the join consumed, so live tokens normally never share a number.
 * The exception is a fork with more outputs than its join consumed: its extra children take the next numbers,
 * which another live token may still hold. When such a duplicate reaches a place where its twin is waiting
 * (queued, or held at a join), the place gives the newer arrival a number not in use there, and records the
 * rename, instead of letting one replace the other.
 *
 * Each token a fork creates carries a stamp (header field origin). The same token delivered twice has the same
 * stamp and is discarded as a repeat; a different token under the same number has a different stamp and is renamed.
 * Tokens made by an event generator carry no stamp: their numbers are unique, so two unstamped tokens with one
 * number are one token delivered twice.
 */
final class TokenNumbers {
	/** Header field: the stamp a fork gives each token it creates. */
	static final String ORIGIN = "origin";

	/** Marks a number a waiting token holds for a later fork (heldIds), rather than its own. */
	static final String HELD = "held:";

	enum Arrival {
		/** No token with this number is waiting here. */
		NEW,
		/** The same token, delivered again: discard the copy. */
		REPEAT,
		/** A different token under a number already waiting here: rename it. */
		DUPLICATE
	}

	private TokenNumbers() { }

	/** A stamp as stored: absent and empty both mean "made by a generator". */
	static String normalise(String origin) {
		return origin == null ? "" : origin.trim();
	}

	/** waitingOrigin is the stamp of the token already waiting under this number, or null if none is. */
	static Arrival classify(String incomingOrigin, String waitingOrigin) {
		if (waitingOrigin == null) return Arrival.NEW;
		if (waitingOrigin.startsWith(HELD)) return Arrival.DUPLICATE;
		return normalise(incomingOrigin).equals(normalise(waitingOrigin)) ? Arrival.REPEAT : Arrival.DUPLICATE;
	}

	/**
	 * A number for a duplicate of id: the next child number of the same workflow (wrapping from 99 to 01)
	 * that is not in use at this place. -1 when every child number of the workflow is in use.
	 * The workflow part (id - id % 100) never changes, so the renamed token still joins its own workflow.
	 */
	static int renumber(int id, Set<Integer> inUse) {
		int family = id - id % 100;
		int child = id % 100;
		for (int step = 1; step < 100; step++) {
			int candidate = family + (child - 1 + step + 99) % 99 + 1;
			if (candidate != id && !inUse.contains(candidate)) return candidate;
		}
		return -1;
	}

	/**
	 * The packet with its token renumbered from oldId to newId: header sequenceId and heldIds, and the token's own ids.
	 * header is the packet's header, already read; it is updated to match.
	 */
	static String renumberPacket(String packet, TreeMap<String, String> header, int oldId, int newId) {
		XPathHelperCommon xph = new XPathHelperCommon();
		TreeMap<String, String> changes = new TreeMap<>();
		changes.put("sequenceId", Integer.toString(newId));
		String held = header.get(ServiceThread.HELD_IDS);
		if (held != null && !held.trim().isEmpty()) {
			TreeSet<Integer> numbers = ServiceThread.heldIds(held, oldId);
			numbers.remove(oldId);
			numbers.add(newId);
			changes.put(ServiceThread.HELD_IDS, ServiceThread.formatHeldIds(numbers, newId));
		}
		header.putAll(changes);
		String renumbered = xph.modifyMultipleXMLItems(packet, "//header/*", changes);
		return renumbered
			.replace("\"tokenId\":\"" + oldId + "\"", "\"tokenId\":\"" + newId + "\"")
			.replace("\"sequenceId\":\"" + oldId + "\"", "\"sequenceId\":\"" + newId + "\"");
	}
}
