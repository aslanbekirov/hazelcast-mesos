package com.hazelcast.mesos.scheduler;

import com.hazelcast.mesos.HazelcastMessages;
import com.hazelcast.mesos.HazelcastNode;
import java.net.URI;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import static com.hazelcast.mesos.util.HazelcastProperties.getCpuPerNode;
import static com.hazelcast.mesos.util.HazelcastProperties.getHazelcastVersion;
import static com.hazelcast.mesos.util.HazelcastProperties.getMaxHeap;
import static com.hazelcast.mesos.util.HazelcastProperties.getMemoryPerNode;
import static com.hazelcast.mesos.util.HazelcastProperties.getMinHeap;
import static com.hazelcast.mesos.util.HazelcastProperties.getNumberOfNodes;
import static com.hazelcast.mesos.util.Util.command;
import static com.hazelcast.mesos.util.Util.resource;
import static com.hazelcast.mesos.util.Util.uris;
import static java.util.Collections.singletonList;


public class HazelcastScheduler implements Scheduler {

    private final URI httpServerURI;
    private volatile int targetNumberOfNodes;

    private Set<HazelcastNode> activeNodes = new HashSet<HazelcastNode>();

    public HazelcastScheduler(URI httpServerURI) {
        this.httpServerURI = httpServerURI;
        this.targetNumberOfNodes = getNumberOfNodes();
    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, Protos.FrameworkID frameworkID, Protos.MasterInfo masterInfo) {
    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, Protos.MasterInfo masterInfo) {
    }

    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Protos.Offer> list) {
        for (Protos.Offer offer : list) {
            Protos.SlaveID slaveId = offer.getSlaveId();
            if (needMoreNodes() && !slaveContainsHazelcastNode(slaveId)) {
                System.out.println("Launching Hazelcast on slaveId = " + slaveId.getValue());
                Protos.TaskID.Builder taskId = Protos.TaskID.newBuilder().setValue("node-" + slaveId.getValue());

                HazelcastMessages.HazelcastServerProcessTask processTask = HazelcastMessages.HazelcastServerProcessTask
                        .newBuilder()
                        .setVersion(getHazelcastVersion())
                        .addCommand("java")
                        .addCommand("-server")
                        .addCommand("-Xms" + getMinHeap())
                        .addCommand("-Xmx" + getMaxHeap())
                        .addCommand("-Djava.net.preferIPv4Stack=true")
                        .addCommand("-cp")
                        .addCommand("hazelcast-" + getHazelcastVersion() + ".jar:hazelcast-zookeeper.jar")
                        .addCommand("com.hazelcast.core.server.StartServer")
                        .build();

                Protos.TaskInfo.Builder builder = Protos.TaskInfo.newBuilder()
                        .setSlaveId(slaveId)
                        .setName("Hazelcast node")
                        .addResources(resource("cpus", getCpuPerNode()))
                        .addResources(resource("mem", getMemoryPerNode()))
                        .setData(processTask.toByteString())
                        .setTaskId(taskId);

                Protos.ExecutorInfo executor = buildExecutor();
                Protos.TaskInfo taskInfo = builder.setExecutor(executor).build();
                schedulerDriver.launchTasks(singletonList(offer.getId()), singletonList(taskInfo));
                HazelcastNode hazelcastNode = new HazelcastNode(offer.getHostname(), slaveId.getValue(), taskId.getValue());
                activeNodes.add(hazelcastNode);
            } else if (needLessNodes() && slaveContainsHazelcastNode(slaveId)) {
                Protos.TaskID taskID = Protos.TaskID.newBuilder().setValue("node-" + slaveId.getValue()).build();
                schedulerDriver.killTask(taskID);
                removeNodeFromActiveNodesWithTaskID(taskID);
            } else {
                schedulerDriver.declineOffer(offer.getId());
            }
        }
    }

    private boolean needLessNodes() {
        return activeNodes.size() > targetNumberOfNodes;
    }

    private boolean needMoreNodes() {
        return activeNodes.size() < targetNumberOfNodes;
    }

    private void removeNodeFromActiveNodesWithTaskID(Protos.TaskID taskID) {
        System.out.println("Removing " + taskID.getValue() + " from active nodes.");
        Iterator<HazelcastNode> iterator = activeNodes.iterator();
        while (iterator.hasNext()) {
            HazelcastNode node = iterator.next();
            if (node.getTaskId().equals(taskID.getValue())) {
                iterator.remove();
            }
        }
    }

    private void removeNodeFromActiveNodesWithSlaveID(Protos.SlaveID slaveID) {
        System.out.println("Removing " + slaveID.getValue() + " from active nodes.");
        Iterator<HazelcastNode> iterator = activeNodes.iterator();
        while (iterator.hasNext()) {
            HazelcastNode node = iterator.next();
            if (node.getSlaveId().equals(slaveID.getValue())) {
                iterator.remove();
            }
        }
    }


    private boolean slaveContainsHazelcastNode(Protos.SlaveID slaveId) {
        for (HazelcastNode node : activeNodes) {
            if (node.getSlaveId().equals(slaveId.getValue())) {
                return true;
            }
        }
        return false;
    }

    private Protos.ExecutorInfo buildExecutor() {
        return Protos.ExecutorInfo.newBuilder()
                .setName("Hazelcast Executor")
                .setExecutorId(Protos.ExecutorID.newBuilder().setValue("HazelcastExecutor - " + System.currentTimeMillis()))
                .setCommand(command("java -cp hazelcast-mesos-executor.jar com.hazelcast.mesos.executor.HazelcastExecutor",
                        uris(
                                getFileURI("hazelcast-" + getHazelcastVersion() + ".jar"),
                                getFileURI("hazelcast-mesos-executor.jar"),
                                getFileURI("hazelcast-zookeeper.jar"),
                                getFileURI("hazelcast.xml")
                        )))
                .setSource("java")
                .build();
    }

    private String getFileURI(String file) {
        return httpServerURI.toString() + file;
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, Protos.OfferID offerID) {
    }

    @Override
    public void statusUpdate(SchedulerDriver schedulerDriver, Protos.TaskStatus taskStatus) {
        Protos.TaskState state = taskStatus.getState();
        if (state == Protos.TaskState.TASK_LOST || state == Protos.TaskState.TASK_FAILED) {
            removeNodeFromActiveNodesWithTaskID(taskStatus.getTaskId());
        }

    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, byte[] bytes) {
    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {
    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, Protos.SlaveID slaveID) {
        removeNodeFromActiveNodesWithSlaveID(slaveID);
    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, int i) {
        removeNodeFromActiveNodesWithSlaveID(slaveID);
    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String s) {
        System.out.println("HazelcastScheduler.error -> " + s);
    }

    public void setTargetNumberOfNodes(int targetNumberOfNodes) {
        this.targetNumberOfNodes = targetNumberOfNodes;
        System.out.println("Current number of nodes = " + activeNodes.size() + ", new target number of nodes = " + targetNumberOfNodes);
    }
}
