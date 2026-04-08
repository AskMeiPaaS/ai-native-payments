package com.ayedata.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Routes a LLM-selected channel name to the correct {@link PaymentSwitch} implementation.
 *
 * <p>All {@link PaymentSwitch} Spring beans are injected at startup. The router builds a
 * case-insensitive alias → switch map from {@link PaymentSwitch#aliases()} so that variations
 * like "UPI Lite", "upi lite", "upi_lite" all resolve to the same switch.
 */
@Component
public class PaymentSwitchRouter {

    private static final Logger log = LoggerFactory.getLogger(PaymentSwitchRouter.class);

    private final Map<String, PaymentSwitch> switchByAlias;

    public PaymentSwitchRouter(List<PaymentSwitch> switches) {
        this.switchByAlias = switches.stream()
                .flatMap(s -> s.aliases().stream()
                        .map(alias -> Map.entry(alias.toLowerCase(), s)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (existing, duplicate) -> existing));

        log.info("PaymentSwitchRouter ready — registered channels: [{}]",
                switches.stream().map(PaymentSwitch::channel).collect(Collectors.joining(", ")));
    }

    /**
     * Resolve a channel string (from the LLM) to the matching {@link PaymentSwitch}.
     *
     * @param channel raw channel name from the LLM (e.g. "UPI", "neft", "UPI Lite")
     * @return the matching {@link PaymentSwitch}
     * @throws IllegalArgumentException if the channel is unknown
     */
    public PaymentSwitch route(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("Payment channel is required");
        }
        String key = channel.toLowerCase().trim().replaceAll("\\s+", " ");
        PaymentSwitch ps = switchByAlias.get(key);
        if (ps == null) {
            String supported = switchByAlias.keySet().stream().sorted().collect(Collectors.joining(", "));
            throw new IllegalArgumentException(
                    "Unsupported payment channel: '" + channel + "'. Supported aliases: " + supported);
        }
        log.debug("Routed '{}' → {}", channel, ps.channel());
        return ps;
    }
}
