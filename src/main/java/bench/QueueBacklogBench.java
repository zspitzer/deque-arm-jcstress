package bench;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * ColdBox's log queue during a burst: a backlog of size entries, the listener draining it with queue.first() +
 * queue.deleteAt( 1 ) while producers keep it topped up (modelled as the drainer appending one entry per dequeue, so
 * the depth stands at size), and a reader walking the queue meanwhile. 1 drainer + 1 reader.
 *
 * This isolates the dequeue cost at depth: O(1) for deque, an O(n) arraycopy per deleteAt( 1 ) for the others, with a
 * concurrent walk in the way. The drainer score is dequeues per microsecond, the reader score walks per microsecond;
 * walkErrors counts walks that threw.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 1, jvmArgsAppend = { "-Xms2g", "-Xmx2g" })
@State(Scope.Group)
public class QueueBacklogBench {

	private static final Object ENTRY = Integer.valueOf(1);

	@Param({ "synclist", "stamped", "deque" })
	String impl;

	@Param({ "10", "1000", "100000" })
	int size;

	Arr queue;

	@Setup(Level.Iteration)
	public void setup() {
		queue = Arrs.filled(impl, size);
	}

	@AuxCounters(AuxCounters.Type.OPERATIONS)
	@State(Scope.Thread)
	public static class Counts {
		public long walkErrors;

		@Setup(Level.Iteration)
		public void reset() {
			walkErrors = 0;
		}
	}

	@State(Scope.Thread)
	public static class Walker {
		final Arrs.Sum sum = new Arrs.Sum();
	}

	@Benchmark
	@Group("burst")
	@GroupThreads(1)
	public void drainer(Blackhole bh) {
		queue.append(ENTRY);
		bh.consume(queue.first());
		queue.deleteAt(1);
	}

	@Benchmark
	@Group("burst")
	@GroupThreads(1)
	public int reader(Counts c, Walker w) {
		Arrs.Sum s = w.sum;
		s.acc = 0;
		try {
			queue.forEach(s);
		}
		catch (RuntimeException e) {
			c.walkErrors++;
		}
		return s.acc;
	}
}
