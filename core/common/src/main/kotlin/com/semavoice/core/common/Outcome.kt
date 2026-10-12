package com.semavoice.core.common

/** A tiny result type so engines never throw across module boundaries. */
sealed class Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>()
    data class Failure(val error: AppError) : Outcome<Nothing>()

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error

    inline fun <R> map(f: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(f(value))
        is Failure -> this
    }

    inline fun onSuccess(f: (T) -> Unit): Outcome<T> { if (this is Success) f(value); return this }
    inline fun onFailure(f: (AppError) -> Unit): Outcome<T> { if (this is Failure) f(error); return this }

    companion object {
        inline fun <T> runCatchingApp(block: () -> T): Outcome<T> = try {
            Success(block())
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Failure(AppError.from(t))
        }
    }
}
