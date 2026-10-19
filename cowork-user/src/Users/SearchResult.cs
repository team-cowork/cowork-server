namespace CoworkUser.Users;

public sealed record SearchResult(IReadOnlyList<UserResponse> Items, int Page, int PageSize, long TotalCount, bool HasNext);
