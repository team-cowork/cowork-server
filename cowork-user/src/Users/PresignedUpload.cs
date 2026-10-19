namespace CoworkUser.Users;

public sealed record PresignedUpload(string UploadUrl, string ObjectKey);
