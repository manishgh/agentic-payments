package io.agenticpayments.provider;

import io.agenticpayments.infrastructure.provider.JdbcProviderEventConfigurations;

/** Independent verifier restart: receives only synthetic scope and a nonsecret descriptor commitment. */
public final class ProviderEventConfigurationRestartProcess {
    public static void main(String[] args) {
        try {
            new JdbcProviderEventConfigurations(ProviderJournalTestSupport.database()).verify(
                    ProviderEventConfigurationStoreIntegrationTest.configuration(args[0],args[1]));
            System.out.print("EVENT_CONFIGURATION_VERIFIED");
        } catch(Throwable unavailable) {System.out.print("EVENT_CONFIGURATION_UNAVAILABLE");System.exit(1);}
    }
}
