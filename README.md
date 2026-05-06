Quote Demo
============================

This application is based on a Quarkus sample available at https://quarkus.io/guides/amqp. 

A small enhancement has been introduced to the backend service to simulate a variable response time resulting in out-of-order responses:
[QuoteProcessor.java](quote-processor/src/main/java/org/acme/amqp/processor/QuoteProcessor.java)

It was originally designed to use the AMQP protocol, but in this branch the configuration is changed to sit on top of Kafka: comparing it with the main branch you'll notice that thanks to Quarkus and Microprofile the required changes are minimal.

### Start the application in dev mode

In a first terminal, run:

```sh
mvn -f quote-producer quarkus:dev
```

In a second terminal, run:

```sh
mvn -f quote-processor quarkus:dev -DdebugPort=5006
```

Then, open your browser to `http://localhost:8080/`, and click on the "Request Quote" button.

Optionally, you can launch the analytics µService:

```sh
mvn -f quote-analytics quarkus:dev -DdebugPort=5007
```

### Start local kafka

If you prefer disable the devservices, you can launch a local Kafka with the following options:

```sh
docker compose up
```

or 

```
podman kube play podman-play.yaml
```

### Build the application in native mode

To build the applications into native executables, run:

```sh
mvn -f quote-producer package -Pnative  -Dquarkus.native.container-build=true
mvn -f quote-processor package -Pnative -Dquarkus.native.container-build=true
```

The `-Dquarkus.native.container-build=true` instructs Quarkus to build Linux 64bits native executables, who can run inside containers.  

### Openshift Deployment

Make sure that Kafka cluster is properly created.
Topic required: `quotes`, `quote-requests` defined.

Application expects to find Kafka bootstrap at `my-cluster-kafka-bootstrap.my-kafka.svc:9092` but it can be chaged in the `ConfigMap` definition [here](quote-processor/src/main/kubernetes/openshift.yml)

Deploy the processor:

```sh
./mvnw -f quote-processor package -DskipTests -Dquarkus.kubernetes.deploy=true
```

Deploy the producer:
```sh
./mvnw -f quote-producer package -DskipTests -Dquarkus.kubernetes.deploy=true
```

Remove builder pods:

```sh
oc delete pods --field-selector=status.phase=Succeeded
```