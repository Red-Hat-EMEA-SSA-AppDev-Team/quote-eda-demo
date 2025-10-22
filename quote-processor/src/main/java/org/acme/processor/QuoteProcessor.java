package org.acme.processor;

import java.util.Random;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.jboss.logging.Logger;

import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A bean consuming data from the "request" and giving out a random quote.
 * The result is pushed to the "quotes".
 */
@ApplicationScoped
public class QuoteProcessor {
    private static final Logger LOG = Logger.getLogger(QuoteProcessor.class);

    private Random random = new Random();

    @Incoming("requests")       // quotes-requests
    @Outgoing("quotes")         // quotes
    @Blocking(ordered = false)  // blocking method
    public Record<String, Integer> process(ConsumerRecord<String, String> record) throws InterruptedException {
        LOG.info("quote serving");
        // simulate some hard working task
        Thread.sleep(200+random.nextInt(10)*200);
        return Record.of(record.key(), random.nextInt(100));
    }
}