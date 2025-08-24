package org.example;


import jdk.incubator.vector.*;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
// ↓↓↓ Add these ↓↓↓
@Fork(1)                  // Only run 1 JVM fork (faster)
@Warmup(iterations = 2)   // Do 2 warmup iterations before measuring
@Measurement(iterations = 3) // Then 3 measured iterations
@Threads(8)   // run with 10 threads
public class MonteCarloBenchmark {

    // Option parameters
    private static final double S0 = 100.0;  // initial stock price
    private static final double K  = 100.0;  // strike
    private static final double r  = 0.05;   // risk-free rate
    private static final double sigma = 0.2; // volatility
    private static final double T = 1.0;     // maturity (1 year)

    private double drift;
    private double vol;
    private double discount;

    private double[] normals;
    private static final int N = 1_000_000; // pool of random numbers
    private Random rng;

    @Param({"10000", "100000", "1000000"})
    int numPaths;

    @Setup(Level.Iteration)
    public void setup() {
        drift = (r - 0.5 * sigma * sigma) * T;
        vol = sigma * Math.sqrt(T);
        discount = Math.exp(-r * T);
        rng = new Random(42);

        // pre-generate normal variates
        normals = new double[N];
        for (int i = 0; i < N; i++) {
            normals[i] = rng.nextGaussian();
        }
    }

    // -------------------------
    // Scalar version
    // -------------------------
    @Benchmark
    public double priceScalar() {
        double payoffSum = 0.0;
        for (int i = 0; i < numPaths; i++) {
            double Z = normals[i % N];
            double ST = S0 * Math.exp(drift + vol * Z);
            payoffSum += Math.max(ST - K, 0.0);
        }
        return discount * (payoffSum / numPaths);
    }

    // -------------------------
    // Vector API version
    // -------------------------
    @Benchmark
    public double priceVector() {
        VectorSpecies<Double> species = DoubleVector.SPECIES_PREFERRED;
        int lanes = species.length();

        double payoffSum = 0.0;

        for (int i = 0; i < numPaths; i += lanes) {
            int upper = Math.min(lanes, numPaths - i);
            var mask = species.indexInRange(0, upper);

            // Load normals directly into vector
            DoubleVector Z = DoubleVector.fromArray(species, normals, i % N, mask);

            // Broadcast initial stock price
            DoubleVector S = DoubleVector.broadcast(species, S0);
            // One-step GBM evolution
            DoubleVector step = Z.mul(vol).add(drift).lanewise(VectorOperators.EXP, mask);
            S = S.mul(step, mask);

            // Payoff = max(S - K, 0)
            DoubleVector tmp = S.sub(K).max(0.0);
            DoubleVector payoff = tmp.blend(DoubleVector.zero(species), mask);
            // Sum payoffs via lane reduction
            payoffSum += payoff.reduceLanes(VectorOperators.ADD, mask);
        }
        return discount * (payoffSum / numPaths);
    }

}
