package com.redhat.demo.kstreams.model;

public class QuoteAggregate {
    private double sum;
    private long count;

    public QuoteAggregate() {
        this.sum = 0.0;
        this.count = 0;
    }

    public QuoteAggregate(double sum, long count) {
        this.sum = sum;
        this.count = count;
    }

    public double getSum() {
        return sum;
    }

    public long getCount() {
        return count;
    }

    public void addPrice(double price) {
        this.sum += price;
        this.count++;
    }

    public void addPrice(Integer price) {
        this.sum += price;
        this.count++;
    }

    public void merge(QuoteAggregate other) {
        this.sum += other.sum;
        this.count += other.count;
    }

    public double getAverage() {
        return (count == 0) ? 0.0 : sum / count;
    }
}