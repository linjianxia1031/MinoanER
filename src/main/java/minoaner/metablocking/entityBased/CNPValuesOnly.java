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

package minoaner.metablocking.entityBased;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import minoaner.utils.Utils;
import org.apache.parquet.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.broadcast.Broadcast;
import scala.Serializable;
import scala.Tuple2;

/**
 *
 * @author vefthym
 */
public class CNPValuesOnly implements Serializable {
    
    public JavaPairRDD<Integer,IntArrayList> run(JavaPairRDD<Integer, IntArrayList> blocksFromEI, Broadcast<Int2FloatOpenHashMap> totalWeightsBV, int K, long numNegativeEntities, long numPositiveEntities) {
        
        Int2FloatOpenHashMap totalWeights = totalWeightsBV.value(); //saves memory by storing data as primitive types                
        totalWeightsBV.unpersist();
        totalWeightsBV.destroy();
        
        JavaPairRDD<Integer, IntArrayList> mapOutput = CNPMapPhase.getMapOutputWJS(blocksFromEI);
               
        //reduce phase
        //the following is cheap to compute (one shuffle needed), but can easily give OOM error
        //metaBlockingResults: key: an entityId, value: an array of topK candidate matches, in descending order of score (match likelihood)
        return mapOutput.groupByKey() //for each entity create an iterable of arrays of candidate matches (one array from each common block)
                .mapToPair(x -> {
                    Integer entityId = x._1();
                    
                    //compute the numerators
                    Int2FloatOpenHashMap counters = new Int2FloatOpenHashMap(); //number of common blocks with current entity per candidate match
                    for(IntArrayList candidates : x._2()) {                       
                        if (candidates.isEmpty()) continue; //still don't know why we need this line... such cases should have been filtered
                        int numNegativeEntitiesInBlock = candidates.getInt(0); //the first element is the number of entities from the same collection
                        int numPositiveEntitiesInBlock = candidates.size()-1; //all the other candidates are positive entity ids
                        if (entityId >= 0) {
                            numPositiveEntitiesInBlock = candidates.getInt(0);
                            numNegativeEntitiesInBlock = candidates.size()-1;
                        }
                        float weight1 = (float) Math.log10((double)numNegativeEntities/numNegativeEntitiesInBlock);
                        float weight2 = (float) Math.log10((double)numPositiveEntities/numPositiveEntitiesInBlock);
                        
                        candidates.removeInt(0); //remove the first element which is the number of entities in this block from the same collection as the entityId
                        
                        for (int neighborId : candidates) {
                            counters.addTo(neighborId, weight1+weight2);                    
                        }
                    }
                    
                    //calculate the weight of each edge in the blocking graph (i.e., for each candidate match)
                    Map<Integer, Float> weights = new HashMap<>();
                    float entityWeight = totalWeights.get(entityId.intValue());
                    for (int neighborId : counters.keySet()) {
			float currentWeight = counters.get(neighborId) / (Float.MIN_NORMAL + entityWeight + totalWeights.get(neighborId));
			weights.put(neighborId, currentWeight);			
                    }
                    
                    //keep the top-K weights
                    weights = Utils.sortByValue(weights, true);                    
                    int[] candidateMatchesSorted = new int[Math.min(weights.size(), K)];                    
                    
                    int i = 0;
                    for (int neighbor : weights.keySet()) {
                        if (i == weights.size() || i == K) {
                            break;
                        }
                        candidateMatchesSorted[i++] = neighbor;                        
                    }
                    
                    return new Tuple2<Integer,IntArrayList>(entityId, new IntArrayList(candidateMatchesSorted));
                });               
    }
}
