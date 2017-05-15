package info.breezes.promise;

import android.util.SparseArray;

public interface Func<P, R> {
    R run(P param, SparseArray<Object> tags);
}