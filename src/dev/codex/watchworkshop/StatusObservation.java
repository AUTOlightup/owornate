package dev.codex.watchworkshop;

/** Last confirmed value and the error from the latest attempt are separate facts. */
final class StatusObservation<T> {
    final T value;
    final long confirmedAt;
    final String error;

    StatusObservation(T value, long confirmedAt, String error) {
        this.value = value;
        this.confirmedAt = value == null ? 0 : Math.max(0, confirmedAt);
        this.error = error == null ? "" : error;
    }

    StatusObservation<T> success(T value, long now) {
        if (value == null) throw new IllegalArgumentException("Missing confirmed value");
        return new StatusObservation<>(value, now, "");
    }

    StatusObservation<T> failure(String error) {
        return new StatusObservation<>(value, confirmedAt, error == null || error.isEmpty() ? "状态读取失败" : error);
    }

    boolean fresh(long now, long ttl) {
        return value != null && error.isEmpty() && confirmedAt > 0 && now >= confirmedAt && now - confirmedAt < ttl;
    }
}
