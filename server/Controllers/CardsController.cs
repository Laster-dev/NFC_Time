using Microsoft.AspNetCore.Mvc;
using NfcServer.Models;
using NfcServer.Services;

namespace NfcServer.Controllers;

[ApiController]
[Route("api/[controller]")]
public class CardsController : ControllerBase
{
    private readonly CardManager _cardManager;

    public CardsController(CardManager cardManager)
    {
        _cardManager = cardManager;
    }

    [HttpGet]
    public IActionResult GetAll()
    {
        return Ok(_cardManager.GetAllCards());
    }

    [HttpGet("{id}")]
    public IActionResult GetById(string id)
    {
        var card = _cardManager.GetCard(id);
        if (card == null) return NotFound(new { message = "Card not found" });
        return Ok(card);
    }

    [HttpPost("swipe")]
    public IActionResult Swipe([FromBody] SwipeRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.CardId))
            return BadRequest(new { message = "CardId is required" });

        var card = _cardManager.HandleSwipe(req.CardId, req.CardName);
        return Ok(card);
    }

    [HttpPost("{id}/timer")]
    public IActionResult SetTimer(string id, [FromBody] SetTimerRequest req)
    {
        var card = _cardManager.SetTimer(id, req.DurationSeconds, req.Action);
        if (card == null) return NotFound(new { message = "Card not found" });
        return Ok(card);
    }

    [HttpPost("{id}/rename")]
    public IActionResult Rename(string id, [FromBody] RenameRequest req)
    {
        if (string.IsNullOrWhiteSpace(req.NewName))
            return BadRequest(new { message = "NewName is required" });

        var card = _cardManager.RenameCard(id, req.NewName);
        if (card == null) return NotFound(new { message = "Card not found" });
        return Ok(card);
    }

    [HttpDelete("{id}")]
    public IActionResult Delete(string id)
    {
        var success = _cardManager.DeleteCard(id);
        if (!success) return NotFound(new { message = "Card not found" });
        return Ok(new { message = "Deleted" });
    }
}
