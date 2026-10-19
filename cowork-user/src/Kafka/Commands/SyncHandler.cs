using CoworkUser.Platform;
using CoworkUser.Users;

namespace CoworkUser.Kafka.Commands;

/// <summary>Consumes user.data.sync (DataGSM student.updated) events.</summary>
public static class SyncHandler
{
    public static async Task HandleAsync(string consumerGroup, KafkaRecord record, ILogger logger)
    {
        var payload = Json.ParseObject(record.Value, out var decodeError);
        if (payload is null)
        {
            await Quarantine(decodeError!);
            return;
        }
        if (!UserSyncContract.Valid(payload.Value, record.KeyText))
        {
            await Quarantine(":invalid_user_sync_contract");
            return;
        }

        Accounts.StudentEventResult result;
        try
        {
            result = await Accounts.ApplyStudentEventAsync(payload.Value);
        }
        catch (FieldErrors errors)
        {
            await Quarantine(errors.Message);
            return;
        }
        catch (Exception exception)
        {
            throw new TransientSyncError($"kafka user sync transient failure without offset commit: {exception.Message}", exception);
        }

        if (result == Accounts.StudentEventResult.Updated)
            logger.LogInformation("Processed Kafka user sync event topic={Topic} partition={Partition} offset={Offset} key={Key}",
                record.Topic, record.Partition, record.Offset, record.KeyText);
        else
            logger.LogWarning("Skipped Kafka user sync event topic={Topic} partition={Partition} offset={Offset} reason={Reason}",
                record.Topic, record.Partition, record.Offset, result == Accounts.StudentEventResult.AccountNotFound ? ":account_not_found" : ":stale_student_event");

        async Task Quarantine(string reason)
        {
            await ActionQuarantine.QuarantineAsync(consumerGroup, record, reason);
            logger.LogWarning("Quarantined invalid Kafka user sync action topic={Topic} partition={Partition} offset={Offset} reason={Reason}",
                record.Topic, record.Partition, record.Offset, reason);
        }
    }
}
