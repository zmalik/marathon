package mesosphere.marathon
package core.task.tracker.impl

import mesosphere.UnitTest
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.TestTaskBuilder
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.Task.LocalVolumeId
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ PathId, Timestamp }
import mesosphere.marathon.stream.Implicits._
import mesosphere.marathon.test.MarathonTestHelper
import org.apache.mesos.{ Protos => MesosProtos }

class TaskSerializerTest extends UnitTest {

  "TaskSerializer" should {
    "minimal marathonTask => Task" in {
      val f = new Fixture
      Given("a minimal MarathonTask")
      val now = MarathonTestHelper.clock.now()
      val taskProto = MarathonTask.newBuilder()
        .setId("task")
        .setVersion(now.toString)
        .setStagedAt(now.millis)
        .setCondition(MarathonTask.Condition.Running)
        .setOBSOLETEHost(f.sampleHost)
        .build()

      When("we convert it to task")
      val task = TaskSerializer.fromProto(taskProto)

      Then("we get a minimal task State")
      val expectedState = TestTaskBuilder.Helper.minimalTask(f.taskId, now, None, Condition.Running)

      task should be(expectedState)

      When("we serialize it again")
      val marathonTask2 = TaskSerializer.toProto(task)

      Then("we get the original state back")
      marathonTask2 should equal(taskProto)
    }

    "full marathonTask with no networking => Task" in {
      val f = new Fixture

      Given("a MarathonTask with all fields and host ports")
      val taskProto = f.completeTask

      When("we convert it to task")
      val task = TaskSerializer.fromProto(taskProto)

      Then("we get the expected task state")
      val expectedState = f.fullSampleTaskStateWithoutNetworking

      task should be(expectedState)

      When("we serialize it again")
      val marathonTask2 = TaskSerializer.toProto(task)

      Then("we get the original state back")
      marathonTask2 should equal(taskProto)
    }

    "full marathonTask with host ports => Task" in {
      val f = new Fixture

      Given("a MarathonTask with all fields and host ports")
      val samplePorts = Seq(80, 81)
      val taskProto =
        f.completeTask.toBuilder
          .addAllPorts(samplePorts.map(Integer.valueOf(_)).asJava)
          .build()

      When("we convert it to task")
      val task = TaskSerializer.fromProto(taskProto)

      Then("we get the expected task state")
      val expectedState = f.fullSampleTaskStateWithoutNetworking.copy(
        status = f.fullSampleTaskStateWithoutNetworking.status.copy(
          networkInfo = f.fullSampleTaskStateWithoutNetworking.status.networkInfo.copy(hostPorts = samplePorts)
        )
      )

      task should be(expectedState)

      When("we serialize it again")
      val marathonTask2 = TaskSerializer.toProto(task)

      Then("we get the original state back")
      marathonTask2 should equal(taskProto)
    }

    "full marathonTask with NetworkInfoList in Status => Task" in {
      val f = new Fixture

      Given("a MarathonTask with all fields and status with network infos")
      val taskProto =
        f.completeTask.toBuilder
          .setStatus(
            MesosProtos.TaskStatus.newBuilder()
              .setTaskId(f.taskId.mesosTaskId)
              .setState(MesosProtos.TaskState.TASK_RUNNING)
              .setContainerStatus(MesosProtos.ContainerStatus.newBuilder().addAllNetworkInfos(f.sampleNetworks.asJava))
          )
          .build()

      When("we convert it to task")
      val task = TaskSerializer.fromProto(taskProto)

      Then("we get the expected task state")
      import MarathonTestHelper.Implicits._
      val expectedState = f.fullSampleTaskStateWithoutNetworking.withNetworkInfo(networkInfos = f.sampleNetworks)

      task should be(expectedState)

      When("we serialize it again")
      val marathonTask2 = TaskSerializer.toProto(task)

      Then("we get the original state back")
      marathonTask2 should equal(taskProto)
    }

    "Reserved <=> Proto" in {
      val f = new Fixture

      Given("a reserved task")
      val proto = f.Resident.reservedProto

      When("We convert it to a task")
      val taskProto = TaskSerializer.fromProto(proto)

      Then("We get a correct representation")
      taskProto should equal(f.Resident.reservedState)

      When("We serialize it again")
      val serialized = TaskSerializer.toProto(taskProto)

      Then("We get the original state back")
      serialized should equal(proto)
    }

    "LaunchedOnReservation <=> Proto" in {
      val f = new Fixture

      Given("a LaunchedOnReservation proto")
      val proto = f.Resident.launchedOnReservationProto

      When("We convert it to a task")
      val task = TaskSerializer.fromProto(proto)

      Then("We get a correct representation")
      task should equal(f.Resident.launchedOnReservationState)

      When("We serialize it again")
      val serialized = TaskSerializer.toProto(task)

      Then("We get the original state back")
      serialized should equal(proto)
    }

    "Failure case: Reserved has no Reservation" in {
      val f = new Fixture

      Given("a Reserved proto missing reservation")
      val proto = f.Resident.reservedProtoWithoutReservation

      When("We convert it to a task")
      val error = intercept[SerializationFailedException] {
        TaskSerializer.fromProto(proto)
      }

      Then("We get a SerializationFailedException")
      error.message should startWith("Unable to deserialize")
    }
  }

  class Fixture {
    private[this] val appId = PathId.fromSafePath("/test")
    val taskId = Task.Id("task")
    val sampleHost: String = "host.some"
    private[this] val sampleAttributes: Seq[MesosProtos.Attribute] = Seq(attribute("label1", "value1"))
    private[this] val stagedAtLong: Long = 1
    private[this] val startedAtLong: Long = 2
    private[this] val appVersion: Timestamp = Timestamp(3)
    private[this] val sampleTaskStatus: MesosProtos.TaskStatus =
      MesosProtos.TaskStatus.newBuilder()
        .setTaskId(MesosProtos.TaskID.newBuilder().setValue(taskId.idString))
        .setState(MesosProtos.TaskState.TASK_RUNNING)
        .build()
    private[this] val sampleSlaveId: MesosProtos.SlaveID.Builder = MesosProtos.SlaveID.newBuilder().setValue("slaveId")
    val sampleNetworks: Seq[MesosProtos.NetworkInfo] =
      Seq(
        MesosProtos.NetworkInfo.newBuilder()
          .addIpAddresses(MesosProtos.NetworkInfo.IPAddress.newBuilder().setIpAddress("1.2.3.4"))
          .build()
      )
    val fullSampleTaskStateWithoutNetworking: Task.LaunchedOnReservation =
      Task.LaunchedOnReservation(
        taskId,
        runSpecVersion = appVersion,
        status = Task.Status(
          stagedAt = Timestamp(stagedAtLong),
          startedAt = Some(Timestamp(startedAtLong)),
          mesosStatus = Some(sampleTaskStatus),
          condition = Condition.Running,
          networkInfo = NetworkInfo(sampleHost, hostPorts = Nil, ipAddresses = Nil)
        ),
        reservation = Task.Reservation(
          Seq(LocalVolumeId(appId, "my-volume", "uuid-123")),
          Task.Reservation.State.Launched)
      )

    val completeTask =
      MarathonTask
        .newBuilder()
        .setId(taskId.idString)
        .setStagedAt(stagedAtLong)
        .setStartedAt(startedAtLong)
        .setVersion(appVersion.toString)
        .setStatus(sampleTaskStatus)
        .setCondition(MarathonTask.Condition.Running)
        .setReservation(MarathonTask.Reservation.newBuilder
          .addLocalVolumeIds(LocalVolumeId(appId, "my-volume", "uuid-123").idString)
          .setState(MarathonTask.Reservation.State.newBuilder()
            .setType(MarathonTask.Reservation.State.Type.Launched)))
        .setOBSOLETEHost(sampleHost)
        .build()

    private[this] def attribute(name: String, textValue: String): MesosProtos.Attribute = {
      val text = MesosProtos.Value.Text.newBuilder().setValue(textValue)
      MesosProtos.Attribute.newBuilder().setName(name).setType(MesosProtos.Value.Type.TEXT).setText(text).build()
    }

    object Resident {
      import scala.concurrent.duration._

      private[this] val appId = PathId("/test")
      private[this] val taskId = Task.Id("reserved1")
      private[this] val agentId = "agent-1"
      private[this] val now = MarathonTestHelper.clock.now()
      private[this] val containerPath = "containerPath"
      private[this] val uuid = "uuid"
      private[this] val attributes = Seq.empty[MesosProtos.Attribute]
      private[this] val localVolumeIds = Seq(Task.LocalVolumeId(appId, containerPath, uuid))
      private[this] val stagedAt = now - 1.minute
      private[this] val startedAt = now - 55.seconds
      private[this] val mesosStatus = TestTaskBuilder.Helper.statusForState(taskId.idString, MesosProtos.TaskState.TASK_RUNNING)
      private[this] val hostPorts = Seq(1, 2, 3)
      private[this] val status = Task.Status(stagedAt, Some(startedAt), Some(mesosStatus), condition = Condition.Running, networkInfo = NetworkInfo(hostName = sampleHost, hostPorts = hostPorts, ipAddresses = Nil))

      def reservedProto = MarathonTask.newBuilder()
        .setId(taskId.idString)
        .setOBSOLETEHost(sampleHost)
        .setCondition(MarathonTask.Condition.Reserved)
        .setVersion(appVersion.toString)
        .setReservation(MarathonTask.Reservation.newBuilder()
          .addAllLocalVolumeIds(localVolumeIds.map(_.idString).asJava)
          .setState(MarathonTask.Reservation.State.newBuilder()
            .setType(MarathonTask.Reservation.State.Type.New)
            .setTimeout(MarathonTask.Reservation.State.Timeout.newBuilder()
              .setInitiated(now.millis)
              .setDeadline((now + 1.minute).millis)
              .setReason(MarathonTask.Reservation.State.Timeout.Reason.ReservationTimeout))))
        .build()

      def reservedState = Task.Reserved(
        Task.Id(taskId.idString),
        reservation = Task.Reservation(localVolumeIds, Task.Reservation.State.New(Some(Task.Reservation.Timeout(
          initiated = now, deadline = now + 1.minute, reason = Task.Reservation.Timeout.Reason.ReservationTimeout)))),
        status = Task.Status(stagedAt = Timestamp(0), condition = Condition.Reserved, networkInfo = NetworkInfo(hostName = sampleHost, hostPorts = Nil, ipAddresses = Nil)),
        runSpecVersion = appVersion
      )

      def launchedEphemeralProto = MarathonTask.newBuilder()
        .setId(taskId.idString)
        .setOBSOLETEHost(sampleHost)
        .setVersion(appVersion.toString)
        .setStagedAt(stagedAt.millis)
        .setStartedAt(startedAt.millis)
        .setStatus(mesosStatus)
        .setCondition(MarathonTask.Condition.Running)
        .addAllPorts(hostPorts.map(Integer.valueOf).asJava)
        .build()

      def launchedOnReservationProto = launchedEphemeralProto.toBuilder
        .setReservation(MarathonTask.Reservation.newBuilder()
          .addAllLocalVolumeIds(localVolumeIds.map(_.idString).asJava)
          .setState(MarathonTask.Reservation.State.newBuilder()
            .setType(MarathonTask.Reservation.State.Type.Launched)))
        .build()

      def launchedOnReservationState = Task.LaunchedOnReservation(
        taskId,
        appVersion,
        status,
        Task.Reservation(localVolumeIds, Task.Reservation.State.Launched)
      )

      def reservedProtoWithoutReservation = reservedProto.toBuilder.clearReservation().build()
    }
  }
}
