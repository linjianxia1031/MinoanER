/*
 * Copyright 2017 vefthym.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package minoaner.evaluation;

import it.unimi.dsi.fastutil.ints.Int2FloatLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import minoaner.metablocking.entityBased.neighbors.CNPNeighbors;
import minoaner.metablocking.preprocessing.BlockFilteringAdvanced;
import minoaner.metablocking.preprocessing.BlocksFromEntityIndex;
import minoaner.metablocking.preprocessing.EntityWeightsWJS;
import minoaner.rankAggregation.LocalRankAggregation;
import minoaner.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.util.LongAccumulator;

/**
 *
 * @author vefthym
 */
public class EvaluateTop1RankedFromEach {
    
    
    
    public static void main(String[] args) {
        String tmpPath;
        String master;
        String inputPath;      
        String groundTruthPath;
        String inputTriples1, inputTriples2;
        String entityIds1, entityIds2;
        
        
        if (args.length == 0) {
            System.setProperty("hadoop.home.dir", "C:\\Users\\VASILIS\\Documents\\hadoop_home"); //only for local mode
            
            tmpPath = "/file:C:\\tmp";
            master = "local[2]";
            inputPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testInput";  
            inputTriples1 = "";
            inputTriples2 = "";
            entityIds1 = "";
            entityIds2 = "";
            groundTruthPath = "/file:C:\\Users\\VASILIS\\Documents\\OAEI_Datasets\\exportedBlocks\\testOutput";            
        } else if (args.length >= 6) {            
            tmpPath = "/file:/tmp";
            //master = "spark://master:7077";
            inputPath = args[0];
            inputTriples1 = args[1];
            inputTriples2 = args[2];
            entityIds1 = args[3];
            entityIds2 = args[4];
            groundTruthPath = args[5];
        } else {
            System.out.println("You can run Metablocking with the following arguments:"
                    + "0: inputBlocking"
                    + "1: inputTriples1 (raw rdf triples)"
                    + "2: inputTriples2 (raw rdf triples)"
                    + "3: entityIds1: entityUrl\tentityId (positive)"
                    + "4: entityIds2: entityUrl\tentityId (also positive)"
                    + "5: ground truth path");
            return;
        }
        
        String appName = "FullMetaBlocking WJS on "+inputPath.substring(inputPath.lastIndexOf("/", inputPath.length()-2)+1);
        SparkSession spark = Utils.setUpSpark(appName, 288, 8, 3, tmpPath);
        int PARALLELISM = spark.sparkContext().getConf().getInt("spark.default.parallelism", 152);        
        JavaSparkContext jsc = JavaSparkContext.fromSparkContext(spark.sparkContext()); 
                       
        
        ////////////////////////
        //start the processing//
        ////////////////////////
        
        //Block Filtering
        System.out.println("\n\nStarting BlockFiltering, reading from "+inputPath);
        LongAccumulator BLOCK_ASSIGNMENTS_ACCUM = jsc.sc().longAccumulator();
        BlockFilteringAdvanced bf = new BlockFilteringAdvanced();
        JavaPairRDD<Integer,IntArrayList> entityIndex = bf.run(jsc.textFile(inputPath), BLOCK_ASSIGNMENTS_ACCUM); 
        entityIndex.setName("entityIndex").cache();
        
        
        //Blocks From Entity Index
        System.out.println("\n\nStarting BlocksFromEntityIndex...");                
        LongAccumulator CLEAN_BLOCK_ACCUM = jsc.sc().longAccumulator();
        LongAccumulator NUM_COMPARISONS_ACCUM = jsc.sc().longAccumulator();        
        BlocksFromEntityIndex bFromEI = new BlocksFromEntityIndex();
        JavaPairRDD<Integer, IntArrayList> blocksFromEI = bFromEI.run(entityIndex, CLEAN_BLOCK_ACCUM, NUM_COMPARISONS_ACCUM);
        blocksFromEI.setName("blocksFromEI").cache(); //a few hundred MBs
        
        
        //get the total weights of each entity, required by WJS weigthing scheme (only)
        System.out.println("\n\nStarting EntityWeightsWJS...");
        EntityWeightsWJS wjsWeights = new EntityWeightsWJS();                
        Int2FloatOpenHashMap totalWeights = new Int2FloatOpenHashMap(wjsWeights.getWeights(blocksFromEI, entityIndex).collectAsMap()); //action
        Broadcast<Int2FloatOpenHashMap> totalWeights_BV = jsc.broadcast(totalWeights);        
        
        double BCin = (double) BLOCK_ASSIGNMENTS_ACCUM.value() / entityIndex.count(); //BCin = average number of block assignments per entity
        final int K = (args.length == 7) ? Integer.parseInt(args[6]) : Math.max(1, ((Double)Math.floor(BCin)).intValue()); //K = |_BCin -1_|
        System.out.println(BLOCK_ASSIGNMENTS_ACCUM.value()+" block assignments");
        System.out.println(CLEAN_BLOCK_ACCUM.value()+" clean blocks");
        System.out.println(NUM_COMPARISONS_ACCUM.value()+" comparisons");
        System.out.println("BCin = "+BCin);
        System.out.println("K = "+K);
        
        entityIndex.unpersist();
        
        long numNegativeEntities = wjsWeights.getNumNegativeEntities();
        long numPositiveEntities = wjsWeights.getNumPositiveEntities();        
        System.out.println("Found "+numNegativeEntities+" negative entities");
        System.out.println("Found "+numPositiveEntities+" positive entities");
        
        //CNP
        System.out.println("\n\nStarting CNP...");
        String SEPARATOR = (inputTriples1.endsWith(".tsv"))? "\t" : " ";        
        final float MIN_SUPPORT_THRESHOLD = 0.01f;
        final int N = 3; //for top-N neighbors
        
        System.out.println("Getting the top K value candidates...");
        CNPNeighbors cnp = new CNPNeighbors();        
        JavaPairRDD<Integer, Int2FloatLinkedOpenHashMap> topKValueCandidates = cnp.getTopKValueSims(blocksFromEI, totalWeights_BV, K, numNegativeEntities, numPositiveEntities);
        
        blocksFromEI.unpersist();        
        
        System.out.println("Getting the top K neighbor candidates...");
        JavaPairRDD<Integer, IntArrayList> topKNeighborCandidates = cnp.run(
                topKValueCandidates, 
                jsc.textFile(inputTriples1, PARALLELISM), 
                jsc.textFile(inputTriples2, PARALLELISM), 
                SEPARATOR, 
                jsc.textFile(entityIds1),
                jsc.textFile(entityIds2),
                MIN_SUPPORT_THRESHOLD, K, N, 
                jsc);
        
        
        ////////////////////////
        //start the processing//
        ////////////////////////
                
        System.out.println("Starting the evaluation...");             
        
        String GT_SEPARATOR = ",";
        if (groundTruthPath.contains("music")) {
            GT_SEPARATOR = " ";
        }
        
        
        JavaPairRDD<Integer,Integer> top1ValueCandidates = topKValueCandidates.mapValues(x -> new IntArrayList(Utils.sortByValue(x, true).keySet()).getInt(0));
        JavaPairRDD<Integer,Integer> top1NeighborCandidates = topKNeighborCandidates.mapValues(x -> x.getInt(0));
        
        //load the results
        top1ValueCandidates.cache();
        JavaPairRDD<Integer,Integer> negativeValueResults = top1ValueCandidates.filter(x -> x._1() < 0); //evaluate negative ids
        JavaPairRDD<Integer,IntArrayList> positiveValueResults = top1ValueCandidates.filter(x-> x._1() >= 0)
                .mapToPair(x -> x.swap()) //evaluate positive ids
                .groupByKey().mapValues(x-> { //many positive ids may have been matched to the same negative id
                    IntArrayList result = new IntArrayList();
                    for (int candidate : x) {
                        result.add(candidate);
                    }                    
                    return result;
                });
        
        top1NeighborCandidates.cache();
        JavaPairRDD<Integer,Integer> negativeNeighborResults = top1NeighborCandidates.filter(x -> x._1() < 0); //evaluate negative ids
        JavaPairRDD<Integer,IntArrayList> positiveNeighborResults = top1NeighborCandidates.filter(x-> x._1() >= 0)
                .mapToPair(x -> x.swap()) //evaluate positive ids
                .groupByKey().mapValues(x-> {  //many positive ids may have been matched to the same negative id
                    IntArrayList result = new IntArrayList();
                    for (int candidate : x) {
                        result.add(candidate);
                    }                    
                    return result;
                });
        
        
        //Start the evaluation        
        LongAccumulator TPs = jsc.sc().longAccumulator("TPs");
        LongAccumulator FPs = jsc.sc().longAccumulator("FPs");
        LongAccumulator FNs = jsc.sc().longAccumulator("FNs");
        EvaluateMatchingResults evaluation = new EvaluateMatchingResults();
        EvaluateBlockingResults blockingEvaluation = new EvaluateBlockingResults();
        
        JavaPairRDD<Integer,Integer> gt = Utils.getGroundTruthIdsFromEntityIds(jsc.textFile(entityIds1, PARALLELISM), jsc.textFile(entityIds2, PARALLELISM), jsc.textFile(groundTruthPath), GT_SEPARATOR);
        gt.cache();        
        
        System.out.println("Finished loading the ground truth with "+ gt.count()+" matches, now evaluating the results...");
        
        
        evaluation.evaluateResults(negativeValueResults, gt, TPs, FPs, FNs);
        System.out.println("\nNegative entity ids value results:");
        EvaluateMatchingResults.printResults(TPs.value(), FPs.value(), FNs.value());   
        
        TPs.reset();
        FPs.reset();
        FNs.reset();
        
        blockingEvaluation.evaluateBlockingResults(positiveValueResults, gt, TPs, FPs, FNs, false);
        System.out.println("\nPositive entity ids value results:");
        EvaluateMatchingResults.printResults(TPs.value(), FPs.value(), FNs.value());   
        
        TPs.reset();
        FPs.reset();
        FNs.reset();
        
        
        evaluation.evaluateResults(negativeNeighborResults, gt, TPs, FPs, FNs);
        System.out.println("\nNegative entity ids neighbor results:");
        EvaluateMatchingResults.printResults(TPs.value(), FPs.value(), FNs.value());   
        
        TPs.reset();
        FPs.reset();
        FNs.reset();
        
        blockingEvaluation.evaluateBlockingResults(positiveNeighborResults, gt, TPs, FPs, FNs, false);
        System.out.println("\nPositive entity ids neighbor results:");
        EvaluateMatchingResults.printResults(TPs.value(), FPs.value(), FNs.value());   
        
        spark.stop();
    }
    
}
