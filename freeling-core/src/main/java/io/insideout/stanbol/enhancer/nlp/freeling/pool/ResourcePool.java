/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.insideout.stanbol.enhancer.nlp.freeling.pool;

import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import org.apache.commons.lang3.concurrent.ConcurrentUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple ResourcePool implementation using a {@link Semaphore} to limit the
 * number of resources and a {@link Queue} to hold the Resources. A
 * {@link ResourceFactory} is used to allow lazzy initialization of the
 * Resources in the pool
 * @author Rupert Westenthaler
 * @author Rafa Haro <rharo@apache.org>
 *
 * @param <T> the type of the resource
 */
public class ResourcePool<T> {

    private final Logger log = LoggerFactory.getLogger(ResourcePool.class);
    
    public static final int DEFAULT_SIZE = 5;

    private int size;
    private int canceled = 0;
    
    private final BlockingQueue<Future<? extends T>> resources; 
    private final ResourceFactory<? extends T> factory;
    private final Map<String,Object> context;

    private boolean closed;
    

    /**
     * 
     * @param maxSize
     * @param factory
     * @param context
     */
    @SuppressWarnings("unchecked")
    public ResourcePool(int size, ResourceFactory<? extends T> factory, Map<String,Object> context) {
        this.factory = factory;
        this.size = size <= 0 ? DEFAULT_SIZE : size;
        this.context = context == null ? Collections.EMPTY_MAP : 
            Collections.unmodifiableMap(context);
        this.resources = new LinkedBlockingQueue<Future<? extends T>>();
        log.info("Initializating Pool of Resources with size: " + this.size);
        for(int i=0; i < this.size; i++){
                resources.add(factory.createResource(context));
            }
    }

    public T getResource(long maxWaitMillis) throws PoolTimeoutException {
        if(closed){
            throw new IllegalStateException("This ResourcePool is already closed");
        }
        
        try {
        	
        	long start = System.currentTimeMillis();
          	while(true){
          		
        		Future<? extends T> resource = resources.take();
        		if(resource.isDone()){
        			log.info("Resource is Done...");
        			return resource.get();
        		}
        		else
        			if(!resource.isCancelled())
        				resources.put(resource); // Back Until initialization is finished
        			else{
        				synchronized(resources){
        					canceled++;
        				}
        				if(canceled == this.size)
        					throw new CancellationException("All Resources have been canceled");
        			}
        		long end = System.currentTimeMillis();
        		if((end-start) > maxWaitMillis)
        			throw new PoolTimeoutException(maxWaitMillis, this.size, resources.size());
        	}	
			
		} catch (InterruptedException e) {
			throw new IllegalStateException("Interupted",e);
		} catch (ExecutionException e) {
			log.warn("Unable to create a Resoruce because of a "
                    + e.getCause().getClass().getSimpleName()
                    + "while creating the Resource using "
                    + factory.getClass().getSimpleName() 
                    + " (message: "+e.getCause().getMessage()
                    + ")!",e);
		}
        
        return null;
     }

    public void returnResource(T res) {
    	if(closed){
    		factory.closeResource(res, context);
    	} else {
    		try {
				resources.put(ConcurrentUtils.constantFuture(res));
			} catch (InterruptedException e) {
				throw new IllegalStateException("Interupted",e);
			} //return to the queue
    	}
    }
    /**
     * Closes this resource pool
     */
    public synchronized void close() {
        if (!closed){    	
        	this.closed = true;
        	int counter = 0;
        	while(counter != this.size){
        		Future<? extends T> f = null;
        		T resource = null;
        		try {
					f = resources.take();
					if(f.isDone())
						resource = f.get();
					else
						f.cancel(true);
				} catch (InterruptedException e) {
					throw new IllegalStateException("Interupted",e);
				} catch (ExecutionException e) {
					throw new IllegalStateException("Error getting state",e);
				}
        		counter++;
        		if(resource != null)
        			factory.closeResource(resource, context);
        	}
        	
        	resources.clear();
        }
    }
    /**
     * Responsible for creating instance for the {@link ResourcePool}. This
     * allows lazzy initialization of the Resources in the Pool
     * @author Rupert Westanthaler
     *
     * @param <T> the type of the resource
     * @param <E> the exception thrown by the Factory
     */
    public static interface ResourceFactory<T> {

        /**
         * Creates a {@link Future} used to get the created resource. The
         * Factory is responsible to manage the {@link ExecutorService} used
         * to create instances. The context can be used to read the state of
         * this {@link ResourcePool}.
         * @param context the context as parsed to the {@link ResourcePool}
         * @return the resource. MUST NOT be <code>null</code>
         * @throws IllegalArgumentException if the context is missing an
         * required information
         */
        Future<T> createResource(Map<String,Object> context);

        /**
         * Request the Factory to close this resource. This allows the factory
         * to free up System resources acquired by Resources.
         * @param resource An resource created by this ResourceFactory instance
         * that is no longer needed by the Pool.
         * @param context the context
         */
        void closeResource(Object resource, Map<String,Object> context);
    }
    
    
}
