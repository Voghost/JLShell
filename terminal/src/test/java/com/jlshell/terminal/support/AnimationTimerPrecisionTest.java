package com.jlshell.terminal.support;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AnimationTimerPrecisionTest {
    @Test void retainsOneLeaseAcrossOverlappingAnimationsAndPairsReleases() {
        var begins=new AtomicInteger();var ends=new AtomicInteger();
        var helper=new AnimationTimerPrecision(true,()->new AnimationTimerPrecision.TimerLibrary(){
            public int begin(int period){assertEquals(1,period);begins.incrementAndGet();return 0;}
            public int end(int period){assertEquals(1,period);ends.incrementAndGet();return 0;}
        });
        Object first=new Object(),second=new Object();
        helper.request(first);helper.request(first);helper.request(second);assertEquals(1,begins.get());
        helper.cancel(first);helper.cancel(first);assertEquals(0,ends.get());
        helper.cancel(second);helper.cancel(second);assertEquals(1,ends.get());
        helper.request(first);helper.cancel(first);assertEquals(2,begins.get());assertEquals(2,ends.get());
    }
    @Test void avoidsNativeLoadingOffWindowsAndFallsBackAfterBindingFailure() {
        var calls=new AtomicInteger();
        var disabled=new AnimationTimerPrecision(false,()->{calls.incrementAndGet();throw new AssertionError();});
        Object owner=new Object();disabled.request(owner);disabled.cancel(owner);assertEquals(0,calls.get());
        var broken=new AnimationTimerPrecision(true,()->{calls.incrementAndGet();throw new UnsatisfiedLinkError("missing winmm");});
        assertDoesNotThrow(()->{broken.request(owner);broken.request(owner);broken.cancel(owner);});
        assertEquals(1,calls.get());
    }
    @Test void doesNotReleaseAPeriodThatWasNotAcquired() {
        var calls=new AtomicInteger();
        var helper=new AnimationTimerPrecision(true,()->new AnimationTimerPrecision.TimerLibrary(){
            public int begin(int period){calls.incrementAndGet();return 1;}
            public int end(int period){fail("No period was acquired");return 0;}
        });
        Object owner=new Object();helper.request(owner);helper.cancel(owner);helper.request(owner);assertEquals(1,calls.get());
    }
}
