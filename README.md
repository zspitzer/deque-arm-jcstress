# deque-arm-jcstress

Throwaway repo: runs the [jcstress](https://github.com/openjdk/jcstress) slot-reuse tests for a lock-free-read deque (`src/main/java/bench/DequeArr.java`, the model behind Lucee's [LDEV-6811](https://luceeserver.atlassian.net/browse/LDEV-6811) `ArrayImpl` port) on GitHub's arm64 and x86 runners.

The question: `fill()` (resize, or a set past the end) bumps the epoch with a release store, then writes the reused slots with plain stores. A release only orders the stores before it, so on a weakly ordered CPU a reader holding stale bounds could see the new slot value without the new epoch. The fix is a `storeStoreFence` after the bump.

- `fenced`: the fix, the default.
- `nofence`: `-Ddeque.fillnofence=true`, the code as first ported.

`ReuseFillSet` and `ReuseFillResize` are the new tests; the other `Reuse*` tests cover append / prepend reuse and should pass everywhere.

```sh
mvn -f jcstress/pom.xml -DskipTests package
java -jar jcstress/target/jcstress.jar -t 'DequeStress\.Reuse' -m stress [-jvmArgsPrepend -Ddeque.fillnofence=true]
```
