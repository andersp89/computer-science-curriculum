package week1;

import edu.princeton.cs.algs4.StdRandom;
import edu.princeton.cs.algs4.StdStats;
import edu.princeton.cs.algs4.StdOut;

public class PercolationStats {	

    private final double[] thresholds;
    private static final double CONFIDENCE_95 = 1.96;
    private final double mean, stddev;
    private final int numberOfTrials;
    
    //perform independent trials on an n-by-n grid
    public PercolationStats(int n, int trials) {

	if (n < 0) throw new IllegalArgumentException("value of n must be greater than zero");
	if (trials < 0) throw new IllegalArgumentException("value of trials must be greater than zero");
	
	//array of doubles to store p threshold (open sites / total sites, at percolation()==true)
	thresholds = new double[trials];
	numberOfTrials = trials;
	int thPointer = 0;
	int numberOfGrids = n * n;
	
	//perform number of trials
	while (trials > 0) {
	    Percolation p = new Percolation(n);

	    while (!p.percolates()) {
		//generate uniform random number, from 1 to n
		int row = StdRandom.uniform(1, n + 1);
		int col = StdRandom.uniform(1, n + 1);
		p.open(row, col);
	    }

	    thresholds[thPointer++] = (double) p.numberOfOpenSites() / (double) (numberOfGrids);
	    trials--;
	}
	
	//calculates mean and std dev. based on array of values
	mean = StdStats.mean(thresholds);
	stddev = StdStats.stddev(thresholds);
    }

    public double mean() {
	return mean;
    }

    public double stddev() {
	return stddev;
    }
    
    //formula for mean - 95 confidence level
    public double confidenceLo() {
	return mean - (CONFIDENCE_95 * Math.sqrt(stddev) / Math.sqrt(numberOfTrials));
    }

    //formula for mean + 95 confidence level
    public double confidenceHi() {
	return mean + (CONFIDENCE_95 * Math.sqrt(stddev) / Math.sqrt(numberOfTrials));
    }
    
    //test client
    public static void main(String[] args) {
	int n = Integer.parseInt(args[0]);
	int trials = Integer.parseInt(args[1]);
	PercolationStats p = new PercolationStats(n, trials);
	StdOut.println("mean = " + p.mean());
	StdOut.println("stddev = " + p.stddev());
	StdOut.println("95% confidence interval = [" + p.confidenceLo() + ", " + p.confidenceHi() + "]");
    }
}