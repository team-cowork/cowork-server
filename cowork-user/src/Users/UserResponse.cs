namespace CoworkUser.Users;

public sealed record UserResponse(
    long Id, string Name, string Email, string Sex, string? GithubId, string? AccountDescription,
    string? StudentRole, string? StudentNumber, string? Major, string? Specialty, string Status,
    string? CustomStatus, string? StatusMessage, DateTime? StatusExpiresAt, string? ProfileImageUrl,
    string? Nickname, IReadOnlyList<string> Roles, string? Description
    );
