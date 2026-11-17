using System.ComponentModel;
using System.ComponentModel.DataAnnotations;
using CoworkUser.Http;

namespace CoworkUser.Users;

public sealed record UserResponse(
    long Id, string Name, string Email, string Sex, string? GithubId, string? AccountDescription,
    string? StudentRole, string? StudentNumber, string? Major, string? Specialty,
    [property: AllowedValues("online", "offline")] string Status,
    [property: MaxLength(30), Example("DO_NOT_DISTURB")] string? CustomStatus,
    [property: MaxLength(100)] string? StatusMessage,
    DateTime? StatusExpiresAt, string? ProfileImageUrl,
    string? Nickname, IReadOnlyList<string> Roles, string? Description,
    [property: Description("프로필 배경 색상 프리셋 키입니다. null이면 클라이언트 기본 배경을 사용합니다."),
               AllowedValues("RED", "BLUE", "GREEN", "AMBER", "PURPLE", "NEUTRAL"), Example("BLUE")]
    string? BannerColor
    );
