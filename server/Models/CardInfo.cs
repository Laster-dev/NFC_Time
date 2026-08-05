using System.Text.Json.Serialization;

namespace NfcServer.Models;

public enum CardStatus
{
    Stopped = 0,
    Running = 1,
    Paused = 2,
    Expired = 3
}

public class CardInfo
{
    public required string CardId { get; set; }
    public string Name { get; set; } = string.Empty;
    public int TargetDurationSeconds { get; set; } = 0;
    public CardStatus Status { get; set; } = CardStatus.Stopped;
    
    public DateTime? StartTimeUtc { get; set; }
    public DateTime? LastSwipeTimeUtc { get; set; }
    public int SavedRemainingSeconds { get; set; } = 0;

    public double RemainingSeconds
    {
        get
        {
            if (Status == CardStatus.Stopped || Status == CardStatus.Paused) return SavedRemainingSeconds;
            if (Status == CardStatus.Running && StartTimeUtc.HasValue)
            {
                var elapsed = (DateTime.UtcNow - StartTimeUtc.Value).TotalSeconds;
                return SavedRemainingSeconds - elapsed;
            }
            return 0;
        }
    }

    public double OverdueSeconds
    {
        get
        {
            var rem = RemainingSeconds;
            return rem < 0 ? Math.Abs(rem) : 0;
        }
    }

    public bool IsOverdue => RemainingSeconds < 0 && (Status == CardStatus.Running || Status == CardStatus.Expired);
}

public class SwipeRequest
{
    public required string CardId { get; set; }
    public string? CardName { get; set; }
}

public class SetTimerRequest
{
    public int DurationSeconds { get; set; }
    public string Action { get; set; } = "start"; // start, pause, stop, reset
}

public class RenameRequest
{
    public required string NewName { get; set; }
}
