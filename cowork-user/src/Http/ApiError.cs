namespace CoworkUser.Http;

/// <summary>A failure mapped to an HTTP status and a user-facing message.</summary>
public sealed class ApiError(int status, string message) : Exception(message)
{
    public int Status { get; } = status;

    public static ApiError NotFound() => new(404, "사용자를 찾을 수 없습니다.");
    public static ApiError Validation(string message) => new(400, message);
}
