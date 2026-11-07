package io.agenticpayments.infrastructure;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.sdk.PaymentAdapter;
import io.agenticpayments.composition.Composition.Ports;
import io.agenticpayments.composition.NativeComposition;
import javax.sql.DataSource;

/** Composition helper kept out of application/domain packages. */
public final class PaymentServices {
    private PaymentServices() {}
    public static PaymentService local(DataSource dataSource) { return local(dataSource,new JdbcSimulatorAdapter(dataSource)); }
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter) {
        return local(dataSource,adapter,NativeComposition.ports(new JdbcSignedArtifactStore(dataSource)));
    }
    /** All native responsibilities can be replaced, decorated or delegated through these same ports. */
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter,Ports ports) {
        return new PaymentService(new JdbcPaymentStore(dataSource,adapter,null,ports));
    }
}
