/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
 
package akka.tutorial.first.java;
 
 
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
//import akka.actor.UntypedActorFactory; // Remove due to deprecation
//import akka.japi.Creator;
//import akka.routing.RoundRobinRouter; // Remove due to deprecation
import akka.routing.RoundRobinPool;
//import com.typesafe.config.Config;
import scala.concurrent.duration.Duration;

import java.util.concurrent.TimeUnit;
 
public class Pi {
 
  // Main
  public static void main(String[] args) {
    Pi pi = new Pi();
    
    // 1, 1, 100 = 3.1315929035585537
    // 1, 100, 1 = 3.1315929035585537
    
    // 5, 50, 50 = 3.141192653605793
    // 5, 5, 500 = 3.1411926536057897
    // 5, 500, 5 = 3.141192653605795
    
    // 5, 500, 500 = 3.1415886535897886
    // 5, 50, 5000 = 3.141588653589783
    // 5, 5000, 50 = 3.141588653589791
    
    // 1st parameter - number of workers
    // 2nd parameter - number of elements to calculate
    // 3rd parameter - number of messages to send to process.
    pi.calculate(8, 100, 100);
  }
 
  
  // Types
  static class Calculate {
  }
 
  static class Work {
    private final int start;
    private final int nrOfElements;
 
    public Work(int start, int nrOfElements) {
      this.start = start;
      this.nrOfElements = nrOfElements;
    }
 
    public int getStart() {
      return start;
    }
 
    public int getNrOfElements() {
      return nrOfElements;
    }
  }
 
  static class Result {
    private final double value;
 
    public Result(double value) {
      this.value = value;
    }
 
    public double getValue() {
      return value;
    }
  }
 
  static class PiApproximation {
    private final double pi;
    private final Duration duration;
 
    public PiApproximation(double pi, Duration duration) {
      this.pi = pi;
      this.duration = duration;
    }
 
    public double getPi() {
      return pi;
    }
 
    public Duration getDuration() {
      return duration;
    }
  }
 
  
 // Actors
  public static class Worker extends UntypedActor {
 	  
    private double calculatePiFor(int start, int nrOfElements) {
      double acc = 0.0;
      for (int i = start * nrOfElements; i <= ((start + 1) * nrOfElements - 1); i++) {
        acc += 4.0 * (1 - (i % 2) * 2) / (2 * i + 1);
      }
      return acc;
    }
 
    public void onReceive(Object message) {
      if (message instanceof Work) {
        Work work = (Work) message;
        double result = calculatePiFor(work.getStart(), work.getNrOfElements());
  	  	System.out.println("2 - worker s:" + work.getStart() + ", n:" + work.getNrOfElements() + ", r:" + result + ", thread " + Thread.currentThread());
        getSender().tell(new Result(result), getSelf());
      } else {
        unhandled(message);
      }
    }
  }
 
  public static class Master extends UntypedActor {
    private final int nrOfMessages;
    private final int nrOfElements;
 
    private double pi;
    private int nrOfResults;
    private final long start = System.currentTimeMillis();
 
    private final ActorRef listener;
    private final ActorRef workerRouter;
 
    public Master(final int nrOfWorkers, int nrOfMessages, int nrOfElements, ActorRef listener) {
      this.nrOfMessages = nrOfMessages;
      this.nrOfElements = nrOfElements;
      this.listener = listener;
      
      
    /*
     * According to : http://doc.akka.io/docs/akka/2.3.11/java/routing.html
     * we need to stop using RoundRobinRouter and use RoundRobinPool with any logic already 
     * build into the framework. 
     * 
     * Old code  (2.0.2):
     * ==================
     *   workerRouter = this.getContext().actorOf(
     *		  new Props(null, Worker.class, null)
     *		  .withRouter(
     *				  //new RoundRobinRouter(nrOfWorkers)
     *				  //new RoundRobinGroup(nrOfWorkers)
	 *		  ),
     *      "workerRouter");
     */

      // New Code (2.3.11):
      // ==================
      workerRouter =
		  getContext().actorOf(new RoundRobinPool(nrOfWorkers).props(Props.create(Worker.class)), 
		    "workerRouter");
      
    }
 
    public void onReceive(Object message) {
      if (message instanceof Calculate) {
    	  System.out.println("1 - **MASTER**, Calculate, thread " + Thread.currentThread());
        for (int start = 0; start < nrOfMessages; start++) {
          workerRouter.tell(new Work(start, nrOfElements), getSelf());
        }
      } else if (message instanceof Result) {
        Result result = (Result) message;
  	  	System.out.println("3 - **MASTER**, Result, r:" + result.getValue() + " thread " + Thread.currentThread());
        pi += result.getValue();
        nrOfResults += 1;
        if (nrOfResults == nrOfMessages) {
          // Send the result to the listener
          Duration duration = Duration.create(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
          listener.tell(new PiApproximation(pi, duration), getSelf());
          // Stops this actor and all its supervised children
          getContext().stop(getSelf());
        }
      } else {
        unhandled(message);
      }
    }
  }
 
  public static class Listener extends UntypedActor {
    public void onReceive(Object message) {
      if (message instanceof PiApproximation) {
    	  System.out.println("4 - Lister, thread " + Thread.currentThread());
        PiApproximation approximation = (PiApproximation) message;
        System.out.println(String.format("\n\tPi approximation: \t%s\n\tCalculation time: \t%s",
            approximation.getPi(), approximation.getDuration()));
        getContext().system().shutdown();
      } else {
        unhandled(message);
      }
    }
  }
 
  
  // Operate pi calculation
  public void calculate(final int nrOfWorkers, final int nrOfElements, final int nrOfMessages) {
    // Create an Akka system
    ActorSystem system = ActorSystem.create("PiSystem");
 
    // create the result listener, which will print the result and shutdown the system
    final ActorRef listener = system.actorOf(Props.create(Listener.class), "listener");
 
    /*
     * According to : http://stackoverflow.com/questions/20181516/solution-for-untypedactor-deprecated-in-akka-java-tutorial
     * we should fix the following to the uncommented code below.
     * 
    // create the master
    ActorRef master1 = system.actorOf(
		new Props(
			new UntypedActorFactory() {
				public UntypedActor create() {
					return new Master(nrOfWorkers, nrOfMessages, nrOfElements, listener);
				}
			}
		), "master");
    
    // Will throw an exception : "cannot use non-static local Creator to create actors; make it static or top-level"
    ActorRef master2 = system.actorOf( 
		Props.create(
            new Creator<Master>(){
                public Master create(){
                    return new Master(nrOfWorkers, nrOfMessages, nrOfElements, listener);
            }
    }), "master");
    */

    // New Code
    ActorRef master = system.actorOf(
		Props.create(
			Master.class, 
			nrOfWorkers, nrOfMessages, nrOfElements, listener), 
		"master"
	);
 
    // start the calculation
    // on 2.3 should get another parameter of actor ref.
    //master.tell(new Calculate());
    master.tell(new Calculate(), ActorRef.noSender());
  
  }

}