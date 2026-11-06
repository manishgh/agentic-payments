package io.agenticpayments.sdk.tck;
import io.agenticpayments.sdk.*;
/** Optional test extension; generic providers are not required to promise this simulator guarantee. */
public interface AtomicNoEffectSealer { Fact sealNoEffect(ChargeOperation operation); }
