package org.btsn.derby.Analysis;
import java.sql.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** A stopped local host supplies metrics with no Monitor, generation, or completion tables. */
public final class CirculatingServiceTimingsCheck {
 public static void main(String[] args) throws Exception {
  Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
  try (Connection c=DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase;create=true"); Statement s=c.createStatement()) {
   s.executeUpdate("CREATE TABLE SERVICEMEASUREMENTS (sequenceID BIGINT, serviceName VARCHAR(100), operation VARCHAR(100), arrivalTime BIGINT, invocationTime BIGINT, publishTime BIGINT)");
   s.executeUpdate("INSERT INTO SERVICEMEASUREMENTS VALUES (1000001,'P1_Place','processToken',1000,1010,1040),(1000001,'P1_Place','processToken',2000,2015,2055),(1000001,'P1_Place','processToken',3000,3020,NULL),(1000001,'P1_Place','processToken',1000,1010,1040),(999000001,'P1_InitializationService','purgeAndInitialize',500,510,520)");
  }
  SwingGanttChart_WithLatency_v1d chart=new SwingGanttChart_WithLatency_v1d();
  check(chart.tasks.size()==3,"Repeated visits, duplicate collection or admin filtering incorrect");
  check(chart.tasks.get(0).elapsedTime==30 && chart.tasks.get(0).queueTime==10,"Arrival/invocation/publish measurements were changed");
  check(chart.tasks.get(1).elapsedTime==40 && chart.tasks.get(1).queueTime==15,"Circulating token lost its second visit");
  check(!chart.tasks.get(2).hasElapsedTime && chart.tasks.get(2).queueTime==20,"Partial service visit invented completion or lost measured queue wait");
  String report=chart.generateWorkflowSummaryReport();
  check(report.contains("3 recorded service invocations") && report.contains("No process completion or Monitor acknowledgement is required"),"Default view still depends on workflow completion");
  chart.setLightQueueBars(true);
  check(chart.generateLaTeXFigure().contains("Service invocation arrival rank"),"Publication export lost visit semantics");
  check(chart.generateLaTeXTable().contains("Service (ms)"),"Publication table still reports workflow elapsed time");
  chart.setSize(chart.getPreferredSize());
  BufferedImage image=new BufferedImage(chart.getWidth(),chart.getHeight(),BufferedImage.TYPE_INT_RGB);
  chart.paint(image.getGraphics()); ImageIO.write(image,"png",Path.of("circulating-local-timings.png").toFile());
  try (Connection c=DriverManager.getConnection("jdbc:derby:ServiceAnalysisDataBase"); Statement s=c.createStatement()) {
   s.executeUpdate("INSERT INTO SERVICEMEASUREMENTS VALUES (1000001,'P1_Place','processToken',1000,1011,1040)");
  }
  chart.setServiceVisitMode(true);
  check(!chart.tasks.get(0).hasElapsedTime && !chart.tasks.get(0).hasQueueTime,"Conflicting collection was silently accepted");
  check(chart.tasks.get(1).hasElapsedTime && chart.tasks.get(1).elapsedTime==40,"One conflicting visit damaged other circulation measurements");
  try (Connection c=DriverManager.getConnection("jdbc:derby:SecondHost;create=true"); Statement s=c.createStatement()) {
   s.executeUpdate("CREATE TABLE SERVICEMEASUREMENTS (sequenceID BIGINT, serviceName VARCHAR(100), operation VARCHAR(100), arrivalTime BIGINT, invocationTime BIGINT, publishTime BIGINT)");
   s.executeUpdate("INSERT INTO SERVICEMEASUREMENTS VALUES (1000002,'P2_Place','processToken',1500,1510,1530)");
  }
  chart.setDatabasePaths(java.util.Arrays.asList("ServiceAnalysisDataBase","SecondHost","SecondHost"));
  check(chart.tasks.size()==4 && chart.tasks.get(1).sequenceId==1000002 && chart.tasks.get(1).elapsedTime==20,
        "Multiple stopped hosts were duplicated or lost chronological interleaving");
  System.out.println("PASS: stopped local database, circulating visits, partial observations, duplicate/conflict handling, default chart and publication exports without Monitor or completion records");
 }
 private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
