# KEDA

To scale your application based on messages in a Kafka topic (like `quote-requests`), you need to define a **TriggerAuthentication** (to handle credentials) and a **ScaledObject** (to define the scaling logic).

Since you are on **OpenShift**, KEDA will use these resources to communicate with your Kafka broker and calculate the lag.



## 1. Create a Secret for Credentials
KEDA needs to authenticate with your Kafka cluster. Assuming you are using SASL/Plaintext, start by creating a secret:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kafka-secrets
  namespace: my-project
type: Opaque
data:
  username: <base64-username>
  password: <base64-password>
```

## 2. Configure Trigger Authentication
This resource tells KEDA how to use those secrets to talk to the "Trigger" (Kafka).

```yaml
apiVersion: keda.sh/v1alpha1
kind: TriggerAuthentication
metadata:
  name: keda-kafka-auth
  namespace: my-project
spec:
  secretTargetRef:
    - parameter: username
      name: kafka-secrets
      key: username
    - parameter: password
      name: kafka-secrets
      key: password
```

## 3. Create the ScaledObject
This is where the magic happens. You link your Deployment to the `quote-requests` topic.

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: kafka-scaledobject
  namespace: my-project
spec:
  scaleTargetRef:
    name: quote-processor     # Your Deployment name
  minReplicaCount: 0          # Will scale to zero if no messages
  maxReplicaCount: 4
  pollingInterval: 30         # Check Kafka every 30 seconds
  cooldownPeriod: 300         # Wait 5 mins before scaling back to zero
  triggers:
  - type: kafka
    metadata:
      bootstrapServers: my-kafka-cluster-kafka-bootstrap.kafka.svc:9092
      topic: quote-requests
      consumerGroup: quote-processor # Should match your app's group
      activationLagThreshold: "0"    # Scaler wakes up at > 0 messages (starts 1st pod)
      lagThreshold: "10"             # Scale up if lag > 10 messages per partition
      offsetResetPolicy: latest
    authenticationRef:
      name: keda-kafka-auth
```


## How it works in practice
1.  **Activation:** KEDA’s operator monitors the Kafka topic lag. If it sees messages in `quote-requests` and your pods are at 0, it scales the deployment to 1.
2.  **HPA Handoff:** Once at 1 pod, KEDA creates an HPA behind the scenes to handle scaling from 1 to 10 based on the `lagThreshold`.
3.  **Scale to Zero:** When the consumer group lag returns to 0 and stays there for the `cooldownPeriod`, KEDA scales the deployment back to 0 to save resources.

## Troubleshooting Tips
* **Consumer Group:** Ensure the `consumerGroup` in the YAML matches the one your application actually uses. If they differ, KEDA will see "lag" that your app is already processing.
* **Check Logs:** If it isn't scaling, check the KEDA operator logs in the `openshift-custom-metrics-autoscaler` namespace:
    ```bash
    oc logs -l name=custom-metrics-autoscaler-operator -n openshift-keda
    ```
* **Network:** Ensure the KEDA operator has network access to your Kafka bootstrap servers (especially if Kafka is outside the cluster).

## Test

Generate workload:

```sh
oc exec -n my-kafka -it my-cluster-broker-0 -- bin/kafka-producer-perf-test.sh \
  --topic quote-requests \
  --num-records 10000 \
  --record-size 16 \
  --throughput 500 \
  --command-property bootstrap.servers=localhost:9092
```

Show current lag:

```sh
oc exec -n my-kafka -it my-cluster-broker-0 -- bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group quote-processor
```

## Scaling Rules

The most common reason KEDA scales a deployment to **1** but no further is that the "math" of your `lagThreshold` hasn't been met yet, or you've hit a "hard ceiling" inherent to how Kafka works.

Here is the breakdown of the rules that trigger the second pod and beyond.



### 1. The "Lag Math" (The Primary Rule)
KEDA uses a specific formula to tell the Horizontal Pod Autoscaler (HPA) how many pods are needed. For the Kafka scaler, the logic is generally:

$$DesiredReplicas = \left\lceil \frac{\text{Total Lag}}{\text{lagThreshold}} \right\rceil$$

* **Scenario:** You set `lagThreshold: "10"`.
* **If Lag is 5:** KEDA scales from 0 to 1 (Activation), but $\lceil 5 / 10 \rceil = 1$. You stay at **1 pod**.
* **If Lag is 11:** Now $\lceil 11 / 10 \rceil = 2$. KEDA triggers the **2nd pod**.
* **If Lag is 100:** $\lceil 100 / 10 \rceil = 10$. KEDA triggers **10 pods**.

**Check this:** If you are sending 1,000 messages but your consumer is very fast, the "Lag" might never actually climb high enough to hit that second multiple of your threshold.



### 2. The "Partition Ceiling" (The Kafka Rule)
This is the most frequent "gotcha." In Kafka, **one partition can only be read by one consumer in a group at a time.**

* If your topic `quote-requests` only has **1 partition**, Kafka will never allow a second pod to help. Even if KEDA spins up 10 pods, 9 of them will sit idle doing nothing.
* **The Rule:** Your `maxReplicaCount` should ideally not exceed the number of partitions in your topic.

**To check your partitions:**
```bash
oc exec -it <kafka-pod> -- bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic quote-requests
```
*Look for "PartitionCount".* If it is 1, you need to increase it to see multiple pods working.



### 3. Activation vs. Scaling Thresholds
KEDA has two different "trigger" levels. If you have both defined, they behave differently:

* **`activationLagThreshold`**: This is the "wake up" call. If lag hits this, scale from 0 $\rightarrow$ 1.
* **`lagThreshold`**: This is the "scaling" metric. Once the app is "awake," this value is used for the math mentioned in Rule #1.

If your `lagThreshold` is set very high (e.g., 500), but your `activationLagThreshold` is low (e.g., 1), the pod will wake up immediately but won't scale further until the lag is massive.



### 4. The HPA "Stabilization Window"
KEDA doesn't scale the pods directly; it creates a standard Kubernetes HPA to do the heavy lifting. HPAs have a **Stabilization Window** (default is usually 0 seconds for scale-up, but 5 minutes for scale-down).

However, if the HPA perceives the metrics as "unstable," it might delay the scale-up to prevent "flapping."

**Check the HPA status to see if it's complaining:**
```bash
oc describe hpa keda-hpa-{your-scaledobject-name}
```
*Look at the "Events" section at the bottom. It will tell you if it's failing to calculate or if it's throttled.*



### 5. Cooldown Period
If your messages are coming in "bursts," KEDA might be hitting the `cooldownPeriod`. If the lag drops to zero even for a second, the scaler might be waiting to see if the traffic is actually sustained before committing to a second pod.



### Troubleshooting

If Keda does not scale the pods as expected try the following:

1.  **Lower your `lagThreshold`:** Set it to `1` or `2` just for testing to see if the second pod triggers.
2.  **Increase Topic Partitions:** Ensure you have at least 2 or 4 partitions.
3.  **Check Consumer Group Name:** Ensure the `consumerGroup` in your KEDA YAML exactly matches the one your application code is using. If they don't match, KEDA is looking at a different "offset" than your app.

