package org.acme.producer;

import java.util.UUID;

import org.acme.model.Quote;
import org.acme.model.QuoteAggregate;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import io.smallrye.mutiny.Multi;
import io.smallrye.reactive.messaging.kafka.KafkaRecord;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/quotes")
public class QuotesResource {

    private static final Logger LOG = Logger.getLogger(QuotesResource.class);

    // Inject a Reactive Messaging Emitter to send messages to the quote-requests
    // channel
    @Channel("quote-requests")
    Emitter<String> quoteRequestEmitter;

    // Injects the quotes stream which is fed by the quote channel
    @Channel("quotes")
    Multi<Record<String, Integer>> quoteRecords;

    // Injects the quotes stream which is fed by the quote channel
    @Channel("quote-aggregates")
    Multi<QuoteAggregate> quoteAggregates;

    /**
     * Endpoint retrieving the "quotes" queue and sending the items to a server sent
     * event.
     */
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS) // Indicates that the content is sent using Server Sent Events
    public Multi<Quote> stream() {
        LOG.info("quote stream for SSE");
        // Returns the (reactive) stream which is fed by the quote channel
        return quoteRecords
                .map(record -> new Quote(record.key(), record.value()));
    }

    /**
     * Endpoint retrieving the "quote-aggregates" queue and sending the items to a
     * server sent
     * event.
     */
    @Path("aggregates")
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS) // Indicates that the content is sent using Server Sent Events
    public Multi<QuoteAggregate> aggregateStream() {
        LOG.info("aggregate stream for SSE");
        return quoteAggregates; // Returns the (reactive) stream which is fed by the quoteAggregates channel
    }

    /**
     * Endpoint to generate a new quote request id and send it to "quote-requests"
     * AMQP queue using the emitter.
     */
    @POST
    @Path("/request")
    @Produces(MediaType.TEXT_PLAIN)
    public String createRequest() {
        LOG.info("new quote request");
        UUID uuid = UUID.randomUUID(); // On a post request, generate a random UUID and
        quoteRequestEmitter.send(KafkaRecord.of(uuid.toString(), "request")); // send it to the quote request queue
                                                                              // using the emitter
        return uuid.toString();
    }
}