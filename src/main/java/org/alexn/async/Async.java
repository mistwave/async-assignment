package org.alexn.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * The `Async` data type is a lazy `Future`, i.e. a way to describe
 * asynchronous computations.
 * <p>
 * It's described by {@link Async#run(Executor, Callback)}, its characteristic
 * function. See {@link Async#eval(Supplier)} for how `Async`
 * values can be built.
 * <p>
 * The assignment, should you wish to accept it, is to fill in the implementation
 * for all functions that are marked with `throw UnsupportedOperationException`.
 */
@FunctionalInterface
public interface Async<A> {
    // type Async<A> = Executor => Callback<A> => void

    /**
     * Characteristic function; every function that needs to be implemented
     * below should be based on calls to `run`.
     * <p>
     * The `executor` is used to schedule tasks for execution.
     * This is important for `flatMap` driven loops, because we need:
     * <p>
     * 1. stack safety, since without an "interpreter", a long loop can blow
     * with a stack overflow
     * 2. fairness, since a long loop can take forever to execute, so
     * by scheduling tasks on the thread pool we are giving a chance
     * for execution to other concurrent tasks
     *
     * @param executor is the thread-pool to use for ensuring fairness and stack-safety.
     * @param cb       is the callback called by the async process when the result is ready.
     */
    void run(Executor executor, Callback<A> cb);

    /**
     * Converts this `Async` to a Java `CompletableFuture`, triggering
     * the computation in the process.
     * <p>
     * IMPLEMENTATION HINT: create a `CompletableFuture`, then call `run`
     * (defined above).
     */
    default CompletableFuture<A> toFuture(Executor executor) {
        CompletableFuture<A> f = new CompletableFuture<>();
        executor.execute(() -> run(executor, f::complete));
        return f;
    }

    /**
     * Given a mapping function, returns a new `Async` value with the
     * result of the source transformed with it.
     *
     * <pre>
     * {@code
     * Async<Integer> fa = Async.eval(() -> 1 + 1)
     *
     * Async<Integer> fb = fa.map(a -> a * 2)
     *
     * Async<String> fc = fb.map(x -> x.toString())
     * }
     * </pre>
     * <p>
     * As a piece of trivia that you don't need to know for this
     * assignment, this function describes a Functor, see:
     * <a href="https://en.wikipedia.org/wiki/Functor">Functor</a>.
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Given that `self` is the source we are transforming, implement
     * an `Async<B>` that's defined in terms of `self.run`.
     */
    default <B> Async<B> map(Function<A, B> f) {
        // not stack-safe, but why???
//        return (executor, cb) ->
//                executor.execute(() ->
//                        run(executor, a -> cb.onSuccess(f.apply(a))
//                        ));
        return (executor, cb) ->
                executor.execute(() ->
                        run(executor,
                                new Callback<A>() {
                                    @Override
                                    public void onSuccess(A value) {
                                        cb.onSuccess(f.apply(value));
                                    }
                                })
                );
    }

    /**
     * Given a mapping function that returns another async result,
     * returns a new `Async` value with the result of the source transformed.
     *
     * <pre>
     * {@code
     * Async<Integer> fa = Async.eval(() -> 1 + 1)
     *
     * Async<Integer> fb = fa.flatMap(a -> Async.eval(() -> a * 2))
     *
     * Async<String> fc = fb.flatMap(x -> Async.eval(() -> x.toString()))
     * }
     * </pre>
     * <p>
     * As a piece of trivia that you don't need to know for this
     * assignment, this is the "monadic bind", see:
     * <a href="https://en.wikipedia.org/wiki/Monad_(functional_programming)">Monad</a>.
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Given that `self` is the source we are transforming, implement
     * an `Async<B>` that's defined in terms of `self.run`.
     */
    default <B> Async<B> flatMap(Function<A, Async<B>> f) {
        return (executor, cb) ->
                executor.execute(() ->
                        run(executor, a ->
                                f.apply(a).run(executor, cb))
                );

    }

    /**
     * Executes the two `Async` values in parallel, executing the given function for
     * producing a final result.
     *
     * <pre>
     * {@code
     * Async<Integer> fa = Async.eval(() -> 1 + 1)
     *
     * Async<Integer> fb = Async.eval(() -> 2 + 2)
     *
     * // Should yield 6
     * Async<Integer> fc = Async.parMap2(fa, fb, (a, b) -> a + b)
     * }
     * </pre>
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Implement an `Async<C>` instance that, on `run`, executes `fa.run` and `fb.run`
     * like so:
     * <p>
     * 1. execution should be parallel
     * 2. on completion the execution should be synchronized and when both complete,
     * that's when the final result should be calculated and returned
     *
     * @param f is the function used to transform the final result
     */
    static <A, B, C> Async<C> parMap2(Async<A> fa, Async<B> fb, BiFunction<A, B, C> f) {
        return (executor, cb) -> fa.toFuture(executor)
                .thenCombine(fb.toFuture(executor), f)
                .thenAcceptAsync(cb::onSuccess);
    }

    static <T> List<T> append(List<T> ts, T t) {
        ArrayList<T> res = new ArrayList<>(ts);
        res.add(t);
        return res;
    }

    /**
     * Given a list of `Async` values, processes all of them and returns the
     * final result as a list.
     * <p>
     * Execution of the given list should be sequential (not parallel).
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Can be implemented in terms of `flatMap`. You start with with an
     * empty "accumulator" (list) and then execute the tasks one by one.
     * <p>
     * Any implementation is accepted, as long as it works.
     */
    static <A> Async<List<A>> sequence(List<Async<A>> list) {
        Async<List<A>> acc = (executor, cb) ->
                list.get(0).run(executor, a -> cb.onSuccess(List.of(a)));
        for (int i = 1; i < list.size(); i++) {
            int finalI = i;
            acc = acc.flatMap(xs ->
                    list.get(finalI).map(x ->
                            append(xs, x)));
        }
        return acc;
    }

    /**
     * Given a list of `Async` values, processes all of them in parallel and
     * returns the final result as a list.
     * <p>
     * Execution of the given list should be parallel.
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Can be implemented in terms of `parMap2`. You start with with an
     * empty "accumulator" (list) and then combine the tasks one by one.
     * <p>
     * Any implementation is accepted, as long as it works.
     */
    static <A> Async<List<A>> parallel(List<Async<A>> list) {
        Async<List<A>> acc = (executor, cb) ->
                list.get(0).run(executor, a -> cb.onSuccess(List.of(a)));

        for (int i = 1; i < list.size(); i++) {
            acc = Async.parMap2(acc, list.get(i), (xs, x) -> append(xs, x));
        }
        return acc;
    }


    /**
     * Wraps an asynchronous process in a safe `Async` implementation.
     * <p>
     * See {@link Async#fromFuture(Supplier)} as example.
     *
     * @param start is a supplied function that should start the asynchronous
     *              process; gets injected with a callback that can be used to
     *              signal the final result
     */
    static <A> Async<A> create(BiConsumer<Executor, Callback<A>> start) {
        return (executor, cb) ->
                // Forcing async boundary (via executor)
                executor.execute(() -> start.accept(executor, Callback.safe(cb)));
    }


    /**
     * Describes an async computation that executes the given `thunk`
     * on the provided `Executor`.
     *
     * <pre>
     * {@code
     * Async<Integer> fa = Async.eval(() -> 1 + 1)
     * }
     * </pre>
     */
    static <A> Async<A> eval(Supplier<A> thunk) {
        return create((executor, cb) -> {
            boolean streamError = true;
            try {
                A value = thunk.get();
                streamError = false;
                cb.onSuccess(value);
            } catch (Exception e) {
                if (streamError) cb.onError(e);
                else throw e;
            }
        });
    }

    /**
     * Wraps a Java `Future` producer into an `Async` type.
     * <p>
     * The supplied value is a function, instead of a straight `Future`
     * reference, because we want it to be lazily evaluated 😉
     * <p>
     * IMPLEMENTATION HINT:
     * <p>
     * Use {@link Async#create(BiConsumer)} described above.
     * See {@link Async#eval(Supplier)} for inspiration
     */
    static <A> Async<A> fromFuture(Supplier<CompletableFuture<A>> f) {
        return create(((executor, cb) -> {
            try {
                A value = f.get().get();
                cb.onSuccess(value);
            } catch (Exception e) {
                cb.onError(e);
            }
        }));
    }

}
