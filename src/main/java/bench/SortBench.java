package bench;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * arraySort on a shuffled array. Today the sort runs in place under the list's monitor; the deque copies, sorts the
 * copy unlocked and publishes it. The array is refilled from the same shuffle before every sort, outside the timing.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class SortBench {

	private static final Comparator<Object> NATURAL = (x, y) -> ((Integer) x).compareTo((Integer) y);

	@Param({ "plain", "synclist", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Object[] shuffled;
	Arr arr;

	@Setup(Level.Trial)
	public void shuffle() {
		shuffled = Arrs.values(size);
		List<Object> l = java.util.Arrays.asList(shuffled);
		Collections.shuffle(l, new Random(42));
	}

	@Setup(Level.Invocation)
	public void fill() {
		arr = Arrs.create(impl);
		for (Object v: shuffled) {
			arr.append(v);
		}
	}

	@Benchmark
	public Arr sort() {
		arr.sort(NATURAL);
		return arr;
	}
}
