using System.Collections.Concurrent;
using NfcServer.Models;

namespace NfcServer.Services;

public class CardManager
{
    private readonly ConcurrentDictionary<string, CardInfo> _cards = new(StringComparer.OrdinalIgnoreCase);

    public IEnumerable<CardInfo> GetAllCards()
    {
        var cards = _cards.Values.ToList();
        foreach (var card in cards)
        {
            // Auto update status to Expired if time ran out while running
            if (card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }
        }
        return cards.OrderByDescending(c => c.LastSwipeTimeUtc ?? DateTime.MinValue);
    }

    public CardInfo? GetCard(string cardId)
    {
        if (_cards.TryGetValue(cardId, out var card))
        {
            if (card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }
            return card;
        }
        return null;
    }

    public CardInfo HandleSwipe(string cardId, string? cardName = null)
    {
        var now = DateTime.UtcNow;
        var card = _cards.GetOrAdd(cardId, id => new CardInfo
        {
            CardId = id,
            Name = !string.IsNullOrWhiteSpace(cardName) ? cardName : $"卡片_{id}",
            LastSwipeTimeUtc = now,
            Status = CardStatus.Stopped
        });

        card.LastSwipeTimeUtc = now;
        if (!string.IsNullOrWhiteSpace(cardName))
        {
            card.Name = cardName;
        }
        return card;
    }

    public CardInfo? SetTimer(string cardId, int durationSeconds, string action)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;

        action = action.ToLower();
        switch (action)
        {
            case "set_and_start":
            case "start":
                if (durationSeconds > 0)
                {
                    card.TargetDurationSeconds = durationSeconds;
                    card.SavedRemainingSeconds = durationSeconds;
                }
                if (card.SavedRemainingSeconds > 0)
                {
                    card.StartTimeUtc = DateTime.UtcNow;
                    card.Status = CardStatus.Running;
                }
                break;

            case "pause":
                if (card.Status == CardStatus.Running)
                {
                    card.SavedRemainingSeconds = (int)Math.Max(0, Math.Round(card.RemainingSeconds));
                    card.Status = CardStatus.Paused;
                    card.StartTimeUtc = null;
                }
                break;

            case "resume":
                if (card.Status == CardStatus.Paused && card.SavedRemainingSeconds > 0)
                {
                    card.StartTimeUtc = DateTime.UtcNow;
                    card.Status = CardStatus.Running;
                }
                break;

            case "stop":
            case "reset":
                card.Status = CardStatus.Stopped;
                card.SavedRemainingSeconds = card.TargetDurationSeconds;
                card.StartTimeUtc = null;
                break;
        }

        return card;
    }

    public CardInfo? RenameCard(string cardId, string newName)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;
        card.Name = newName;
        return card;
    }

    public bool DeleteCard(string cardId)
    {
        return _cards.TryRemove(cardId, out _);
    }
}
