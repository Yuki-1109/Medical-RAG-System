package com.medicalrag.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;

/** Trusted internal callback. The channel is read-only, verified and valid only during this call. */
@FunctionalInterface
public interface OriginalInspection<T> {
    T inspect(FileChannel original) throws IOException;
}
