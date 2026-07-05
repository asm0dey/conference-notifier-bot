package cfpbot

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.LocalTime
import javax.sql.DataSource

fun cfpCheckTask(check: CheckTask, runAt: LocalTime) =
    Tasks.recurring("cfp-check", Schedules.daily(runAt))
        .execute { _, _ ->
            // db-scheduler runs this on its own thread pool; runBlocking bridges to the suspend world.
            runBlocking { check.run() }
        }

fun drainQueueTask(drainer: QueueDrainer) =
    Tasks.recurring("drain-queue", Schedules.fixedDelay(Duration.ofMinutes(2)))
        .execute { _, _ ->
            runBlocking { drainer.drain() }
        }

fun startScheduler(ds: DataSource, check: CheckTask, runAt: LocalTime, drainer: QueueDrainer): Scheduler {
    // startTasks(...) — NOT create(ds, tasks...) — is what inserts the initial execution row for a
    // static recurring task. create()'s varargs only register a task as *known* (executable if a row
    // already exists); without startTasks the daily check + queue drain never fire. See db-scheduler
    // README "Recurring task (static)".
    val scheduler = Scheduler.create(ds)
        .startTasks(cfpCheckTask(check, runAt), drainQueueTask(drainer))
        .threads(2)
        .build()
    scheduler.start()
    return scheduler
}
