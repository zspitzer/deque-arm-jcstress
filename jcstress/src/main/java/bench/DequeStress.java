package bench;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE_INTERESTING;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.III_Result;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * jcstress for DequeArr: holder publication, bounds, tombstone retry, slot reuse, snapshot walks, lazy-lock inflation.
 *
 * Elements are Integers. Read outcomes are encoded as ints: the element, -1 for out of range / empty, -2 if a
 * TOMBSTONE leaked out, -3 for null, -4 for any other exception. Unlisted outcomes are FORBIDDEN.
 */
public final class DequeStress {

	private DequeStress() {}

	static DequeArr of(int... vs) {
		DequeArr a = new DequeArr();
		for (int v: vs) {
			a.append(v);
		}
		return a;
	}

	static int code(DequeArr a, int key) {
		try {
			Object v = a.get(key);
			if (v == DequeArr.TOMBSTONE) return -2;
			if (v == null) return -3;
			return (Integer) v;
		}
		catch (IndexOutOfBoundsException e) {
			return -1;
		}
		catch (RuntimeException e) {
			return -4;
		}
	}

	// elements as digits of v + 1, so 0 is representable: [ 0, 1, 2 ] walks to 123
	static int walk(DequeArr a) {
		int[] acc = { 0 };
		a.forEach(o -> {
			if (o == DequeArr.TOMBSTONE || o == null) acc[0] = -1_000_000_000;
			else acc[0] = acc[0] * 10 + (Integer) o + 1;
		});
		return acc[0];
	}

	// ---- publication ----

	@JCStressTest
	@Description("Append into an empty array: a reader sees nothing, or the element, never null.")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "before the append")
	@Outcome(id = "7", expect = ACCEPTABLE, desc = "after the append")
	@State
	public static class AppendPublish {
		final DequeArr a = new DequeArr();

		@Actor
		public void writer() {
			a.append(7);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, -1);
		}
	}

	@JCStressTest
	@Description("Append at capacity grows and publishes a new holder. Old elements stay visible, and once the new last is seen by -1 it stays seen by key 11.")
	@Outcome(id = "9, -1, 0", expect = ACCEPTABLE, desc = "before the append")
	@Outcome(id = "9, 10, 0", expect = ACCEPTABLE, desc = "append landed between the reads")
	@Outcome(id = "10, 10, 0", expect = ACCEPTABLE, desc = "after the append")
	@State
	public static class GrowthPublish {
		final DequeArr a = of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

		@Actor
		public void writer() {
			a.append(10);
		}

		@Actor
		public void reader(III_Result r) {
			r.r1 = code(a, -1);
			r.r2 = code(a, 11);
			r.r3 = code(a, 1);
		}
	}

	@JCStressTest
	@Description("Prepend with no front slack grows and republishes. The last element never moves.")
	@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "before the prepend")
	@Outcome(id = "9, 1", expect = ACCEPTABLE, desc = "after the prepend")
	@State
	public static class PrependGrowth {
		final DequeArr a = of(0, 1);

		@Actor
		public void writer() {
			a.prepend(9);
		}

		@Actor
		public void reader(II_Result r) {
			r.r1 = code(a, 1);
			r.r2 = code(a, -1);
		}
	}

	// ---- removal and the tombstone retry ----

	@JCStressTest
	@Description("pop against a read of the end: the old or the new last, never a tombstone, and no resurrection.")
	@Outcome(id = "2, 2", expect = ACCEPTABLE, desc = "before the pop")
	@Outcome(id = "2, -1", expect = ACCEPTABLE, desc = "pop landed between the reads")
	@Outcome(id = "1, -1", expect = ACCEPTABLE, desc = "after the pop")
	@State
	public static class PopVsLast {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.pop();
		}

		@Actor
		public void reader(II_Result r) {
			r.r1 = code(a, -1);
			r.r2 = code(a, 3);
		}
	}

	@JCStressTest
	@Description("deleteAt( 1 ) against a read of the front: the old or the new first, never a tombstone.")
	@Outcome(id = "0, 0", expect = ACCEPTABLE, desc = "before the shift")
	@Outcome(id = "0, -1", expect = ACCEPTABLE, desc = "shift landed between the reads")
	@Outcome(id = "1, -1", expect = ACCEPTABLE, desc = "after the shift")
	@State
	public static class ShiftVsFirst {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
		}

		@Actor
		public void reader(II_Result r) {
			r.r1 = code(a, 1);
			r.r2 = code(a, -3);
		}
	}

	// ---- slot reuse: a tombstoned slot rewritten in the same holder while a reader holds stale bounds ----

	@JCStressTest
	@Description("End stack, pop pop append, against arr[ -2 ]. States [0,1,2] [0,1] [0] [0,9] give 1, 0, -1, 0. 9 was never at -2: a stale reader indexes slot 1 after the append reuses it.")
	@Outcome(id = "1", expect = ACCEPTABLE, desc = "[0,1,2]")
	@Outcome(id = "0", expect = ACCEPTABLE, desc = "[0,1] or [0,9]")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "[0]")
	@Outcome(id = "9", expect = FORBIDDEN, desc = "wrong element via slot reuse")
	@State
	public static class ReuseEndNegativeKey {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.pop();
			a.pop();
			a.append(9);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, -2);
		}
	}

	@JCStressTest
	@Description("Front stack, shift shift prepend, against arr[ 2 ]. States [0,1,2] [1,2] [2] [9,2] give 1, 2, -1, 2. 9 was never at 2: the prepend reuses slot 1.")
	@Outcome(id = "1", expect = ACCEPTABLE, desc = "[0,1,2]")
	@Outcome(id = "2", expect = ACCEPTABLE, desc = "[1,2] or [9,2]")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "[2]")
	@Outcome(id = "9", expect = FORBIDDEN, desc = "wrong element via slot reuse")
	@State
	public static class ReuseFrontPositiveKey {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
			a.deleteAt(1);
			a.prepend(9);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, 2);
		}
	}

	@JCStressTest
	@Description("pop, shift, append against arr[ 2 ]. States [0,1] [0] [] [9] give 1, -1, -1, -1. The append reuses slot 1 with head moved.")
	@Outcome(id = "1", expect = ACCEPTABLE, desc = "[0,1]")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "[0], [] or [9]")
	@Outcome(id = "9", expect = FORBIDDEN, desc = "wrong element via slot reuse")
	@State
	public static class ReuseMixedPositiveKey {
		final DequeArr a = of(0, 1);

		@Actor
		public void writer() {
			a.pop();
			a.deleteAt(1);
			a.append(9);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, 2);
		}
	}

	// fill's slot stores are plain, so only a storeStoreFence after the epoch bump keeps them behind it. x86 never
	// reorders stores, so these need a weakly ordered CPU (ARM) to fail; -Ddeque.fillnofence=true is the negative control

	@JCStressTest
	@Description("shift, pop, then arr[ 2 ] = 9 past the end, against arr[ 3 ]. States [0,1,2] [1,2] [1] [1,9] give 2, -1, -1, -1. 9 was never at 3: the fill reuses slot 2 with head moved.")
	@Outcome(id = "2", expect = ACCEPTABLE, desc = "[0,1,2]")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "[1,2], [1] or [1,9]")
	@Outcome(id = "9", expect = FORBIDDEN, desc = "wrong element via slot reuse by fill")
	@State
	public static class ReuseFillSet {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
			a.pop();
			a.set(2, 9);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, 3);
		}
	}

	@JCStressTest
	@Description("shift, pop, then resize( 2 ), against arr[ 3 ]. States [0,1,2] [1,2] [1] [1,null] give 2, -1, -1, -1. A null at 3 never existed: the resize pads into slot 2 with head moved.")
	@Outcome(id = "2", expect = ACCEPTABLE, desc = "[0,1,2]")
	@Outcome(id = "-1", expect = ACCEPTABLE, desc = "[1,2], [1] or [1,null]")
	@Outcome(id = "-3", expect = FORBIDDEN, desc = "null via slot reuse by fill")
	@State
	public static class ReuseFillResize {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
			a.pop();
			a.resize(2);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = code(a, 3);
		}
	}

	// ---- snapshot walks ----

	@JCStressTest
	@Description("Queue shape, shift then append, against a live walk: each step reads key k in the state at that moment, states [0,1,2] [1,2] [1,2,3]. Never a tombstone, never an element twice.")
	@Outcome(id = "123", expect = ACCEPTABLE, desc = "all steps in [0,1,2]")
	@Outcome(id = "12", expect = ACCEPTABLE, desc = "0, 1, then key 3 past the end of [1,2]")
	@Outcome(id = "124", expect = ACCEPTABLE, desc = "0, 1, then key 3 of [1,2,3]")
	@Outcome(id = "13", expect = ACCEPTABLE, desc = "0, then key 2 of [1,2], then the end")
	@Outcome(id = "134", expect = ACCEPTABLE, desc = "0, then keys 2 and 3 of [1,2,3]")
	@Outcome(id = "23", expect = ACCEPTABLE, desc = "all steps in [1,2]")
	@Outcome(id = "234", expect = ACCEPTABLE, desc = "ends in [1,2,3]")
	@State
	public static class WalkVsQueue {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
			a.append(3);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = walk(a);
		}
	}

	@JCStressTest
	@Description("Rotate through a reused slot: shift 0, pop 2, append 0, against a live walk. States [0,1,2] [1,2] [1] [1,0]. Every step is a real element of the state it read; index-based, so 0 can be yielded twice, as today's ListIteratorImpl.")
	@Outcome(id = "123", expect = ACCEPTABLE, desc = "all steps in [0,1,2]")
	@Outcome(id = "12", expect = ACCEPTABLE, desc = "0, 1, then the end")
	@Outcome(id = "13", expect = ACCEPTABLE, desc = "0, then key 2 of [1,2]")
	@Outcome(id = "1", expect = ACCEPTABLE, desc = "0, then key 2 past the end of [1]")
	@Outcome(id = "23", expect = ACCEPTABLE, desc = "[1,2]")
	@Outcome(id = "2", expect = ACCEPTABLE, desc = "1, then the end of [1]")
	@Outcome(id = "21", expect = ACCEPTABLE, desc = "ends in [1,0]")
	@Outcome(id = "11", expect = ACCEPTABLE_INTERESTING, desc = "0 at key 1 of [0,1,2], then 0 again at key 2 of [1,0]: live-walk doubling")
	@State
	public static class WalkReuseDoubled {
		final DequeArr a = of(0, 1, 2);

		@Actor
		public void writer() {
			a.deleteAt(1);
			a.pop();
			a.append(0);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = walk(a);
		}
	}

	// ---- middle deletes, shifted in place inside an odd epoch ----

	// toArray as digits of v + 1, like walk
	static int copy(DequeArr a) {
		int acc = 0;
		for (Object o: a.toArray()) {
			if (o == DequeArr.TOMBSTONE || o == null) return -1_000_000_000;
			acc = acc * 10 + (Integer) o + 1;
		}
		return acc;
	}

	@JCStressTest
	@Description("deleteAt( 2 ) on [0,1,2,3,4] shifts the front side: 0 moves up one slot, then head + 1. A torn read with the old bounds sees 0 at key 2.")
	@Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "before the delete")
	@Outcome(id = "2, 0", expect = ACCEPTABLE, desc = "after the delete")
	@State
	public static class MiddleFrontShiftVsGet {
		final DequeArr a = of(0, 1, 2, 3, 4);

		@Actor
		public void writer() {
			a.deleteAt(2);
		}

		@Actor
		public void reader(II_Result r) {
			r.r1 = code(a, 2);
			r.r2 = code(a, 1);
		}
	}

	@JCStressTest
	@Description("deleteAt( 4 ) on [0,1,2,3,4,5] shifts the back side: slots 4 and 5 move down one, then end - 1. Reading key 4 then key 5, a torn read sees 4 twice.")
	@Outcome(id = "3, 4", expect = ACCEPTABLE, desc = "both before the delete")
	@Outcome(id = "3, 5", expect = ACCEPTABLE, desc = "the delete landed between the reads")
	@Outcome(id = "4, 5", expect = ACCEPTABLE, desc = "both after the delete")
	@State
	public static class MiddleBackShiftVsGet {
		final DequeArr a = of(0, 1, 2, 3, 4, 5);

		@Actor
		public void writer() {
			a.deleteAt(4);
		}

		@Actor
		public void reader(II_Result r) {
			r.r1 = code(a, 4);
			r.r2 = code(a, 5);
		}
	}

	@JCStressTest
	@Description("deleteAt( 2 ) front shift against toArray: the copy is the array before or after, never a torn [0,0,2,3,4].")
	@Outcome(id = "12345", expect = ACCEPTABLE, desc = "before the delete")
	@Outcome(id = "1345", expect = ACCEPTABLE, desc = "after the delete")
	@State
	public static class MiddleFrontShiftVsToArray {
		final DequeArr a = of(0, 1, 2, 3, 4);

		@Actor
		public void writer() {
			a.deleteAt(2);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = copy(a);
		}
	}

	@JCStressTest
	@Description("deleteAt( 3 ) back shift against toArray: the copy is the array before or after, never a torn [0,1,3,3,4].")
	@Outcome(id = "12345", expect = ACCEPTABLE, desc = "before the delete")
	@Outcome(id = "1245", expect = ACCEPTABLE, desc = "after the delete")
	@State
	public static class MiddleBackShiftVsToArray {
		final DequeArr a = of(0, 1, 2, 3, 4);

		@Actor
		public void writer() {
			a.deleteAt(3);
		}

		@Actor
		public void reader(I_Result r) {
			r.r1 = copy(a);
		}
	}

	// ---- lazy lock ----

	@JCStressTest
	@Description("Three writers appending twice each: nothing lost across thin-lock contention and inflation.")
	@Outcome(id = "6, 12, 0", expect = ACCEPTABLE, desc = "thin lock only")
	@Outcome(id = "6, 12, 1", expect = ACCEPTABLE_INTERESTING, desc = "inflated to the ReentrantLock")
	@State
	public static class LazyLockAppend {
		final DequeArr a = new DequeArr();

		@Actor
		public void w1() {
			a.append(1);
			a.append(1);
		}

		@Actor
		public void w2() {
			a.append(2);
			a.append(2);
		}

		@Actor
		public void w3() {
			a.append(3);
			a.append(3);
		}

		@Arbiter
		public void check(III_Result r) {
			int[] sum = { 0 };
			a.forEach(o -> sum[0] += (Integer) o);
			r.r1 = a.size();
			r.r2 = sum[0];
			r.r3 = a.inflated() ? 1 : 0;
		}
	}

	@JCStressTest
	@Description("Two writers each pop one of two elements: the same element is never removed twice.")
	@Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "")
	@Outcome(id = "1, 0", expect = ACCEPTABLE, desc = "")
	@State
	public static class LazyLockPop {
		final DequeArr a = of(0, 1);

		@Actor
		public void w1(II_Result r) {
			r.r1 = (Integer) a.pop();
		}

		@Actor
		public void w2(II_Result r) {
			r.r2 = (Integer) a.pop();
		}
	}
}
