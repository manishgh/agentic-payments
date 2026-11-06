package io.agenticpayments.infrastructure;

import io.agenticpayments.application.PaymentService;
import io.agenticpayments.sdk.PaymentAdapter;
import javax.sql.DataSource;

/** Composition helper kept out of application/domain packages. */
public final class PaymentServices {
    private PaymentServices() {}
    public static PaymentService local(DataSource dataSource) { return local(dataSource,new JdbcSimulatorAdapter(dataSource)); }
    public static PaymentService local(DataSource dataSource,PaymentAdapter adapter) { return new PaymentService(new JdbcPaymentStore(dataSource,adapter)); }
}
