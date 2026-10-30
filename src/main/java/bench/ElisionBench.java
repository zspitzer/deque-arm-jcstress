package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * C2 lock elision on a request-local append loop whose array never escapes. One method per impl with a direct
 * constructor call, so escape analysis sees a single allocation site (Arrs.create's switch would merge them).
 *
 * Run once with default flags and once with -XX:-EliminateLocks. If synclist slows down with elimination off, today's
 * monitor was being elided or coarsened, and deque's thin-lock CAS may not get the same treatment.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Thread)
public class ElisionBench {

	@Param({ "10", "100", "1000" })
	int size;

	Object[] values;

	@Setup
	public void setup() {
		values = Arrs.values(size);
	}

	// ns per whole build, the array is dropped after size()
	@Benchmark
	public int plain() {
		PlainArr a = new PlainArr();
		for (Object v: values) {
			a.append(v);
		}
		return a.size();
	}

	@Benchmark
	public int synclist() {
		SyncListArr a = new SyncListArr();
		for (Object v: values) {
			a.append(v);
		}
		return a.size();
	}

	@Benchmark
	public int deque() {
		DequeArr a = new DequeArr();
		for (Object v: values) {
			a.append(v);
		}
		return a.size();
	}
}
