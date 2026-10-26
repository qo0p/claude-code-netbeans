package dev.claudecode.netbeans;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;

/** Runs code on the Swing event thread and hands the result back. */
final class Edt {

    private Edt() {
    }

    static <T> T call(Callable<T> c) throws Exception {
        if (EventQueue.isDispatchThread()) {
            return c.call();
        }
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        try {
            EventQueue.invokeAndWait(() -> {
                try {
                    result[0] = c.call();
                } catch (Exception e) {
                    error[0] = e;
                }
            });
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause();
            if (t instanceof Exception) {
                throw (Exception) t;
            }
            throw new IllegalStateException(t);
        }
        if (error[0] != null) {
            throw error[0];
        }
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }

    static void later(Runnable r) {
        EventQueue.invokeLater(r);
    }
}
