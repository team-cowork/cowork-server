using System.Net;
using System.Text.RegularExpressions;
using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;

namespace CoworkUser;

public static partial class ObjectStorage
{
    static readonly string[] SupportedTypes = ["image/jpeg", "image/png", "image/webp"];

    [GeneratedRegex(@"^(profiles|profile-uploads)/[1-9][0-9]*/[0-9a-f-]{36}\.(jpeg|jpg|png|webp)$")]
    private static partial Regex ProfileKey();

    static readonly Lazy<AmazonS3Client> Internal = new(() => Client(App.Config.S3InternalEndpoint));
    static readonly Lazy<AmazonS3Client> Signing = new(() => Client(App.Config.PublicApiBaseUrl));

    static AmazonS3Client Client(string endpoint) => new(
        new BasicAWSCredentials(App.Config.S3AccessKey, App.Config.S3SecretKey),
        new AmazonS3Config
        {
            ServiceURL = endpoint,
            ForcePathStyle = App.Config.S3PathStyle,
            AuthenticationRegion = App.Config.S3Region,
            // SeaweedFS rejects the SDK's default flexible checksums.
            RequestChecksumCalculation = RequestChecksumCalculation.WHEN_REQUIRED,
            ResponseChecksumValidation = ResponseChecksumValidation.WHEN_REQUIRED,
        });

    public static bool ContentTypeAllowed(string? contentType) =>
        contentType is not null && App.Config.AllowedContentTypes.Contains(contentType);

    public static string BuildObjectKey(long userId, string contentType) =>
        $"profile-uploads/{userId}/{Guid.NewGuid()}.{contentType.Split('/')[^1]}";

    public static string PresignedPutUrl(string objectKey, string contentType) => Presign(new GetPreSignedUrlRequest
    {
        Verb = HttpVerb.PUT,
        ContentType = contentType,
        Key = objectKey,
        Expires = DateTime.UtcNow.AddMinutes(App.Config.PresignedPutExpiryMinutes),
    });

    public static string? PresignedGetUrl(string objectKey)
    {
        try
        {
            return Presign(new GetPreSignedUrlRequest
            {
                Verb = HttpVerb.GET,
                Key = objectKey,
                Expires = DateTime.UtcNow.AddMinutes(App.Config.PresignedGetExpiryMinutes),
            });
        }
        catch (Exception)
        {
            return null;
        }
    }

    static string Presign(GetPreSignedUrlRequest request)
    {
        request.BucketName = App.Config.S3Bucket;
        request.Protocol = new Uri(App.Config.PublicApiBaseUrl).Scheme == "https" ? Protocol.HTTPS : Protocol.HTTP;
        var signed = new Uri(Signing.Value.GetPreSignedURL(request));
        // Gateway restores the signed API Host and removes only /api/storage.
        return $"{App.Config.PublicApiBaseUrl.TrimEnd('/')}/api/storage{signed.AbsolutePath}{signed.Query}";
    }

    /// <summary>Copies a verified upload to its final key and returns that key.</summary>
    public static async Task<string> VerifyUploadAsync(long userId, string? objectKey)
    {
        if (!OwnedKey(objectKey, "profile-uploads", userId))
            throw ApiError.Validation("유효하지 않은 object_key입니다.");

        var config = App.Config;
        try
        {
            var head = await Internal.Value.GetObjectMetadataAsync(config.S3Bucket, objectKey);
            var metadata = ValidateMetadata(head.ContentLength, head.Headers.ContentType, head.ETag,
                config.MaxFileSizeBytes, config.AllowedContentTypes);
            var finalKey = $"profiles/{userId}/{Guid.NewGuid()}.{Extension(metadata.ContentType)}";
            var copy = new CopyObjectRequest
            {
                SourceBucket = config.S3Bucket,
                SourceKey = objectKey,
                DestinationBucket = config.S3Bucket,
                DestinationKey = finalKey,
                ETagToMatch = metadata.ETag,
                MetadataDirective = S3MetadataDirective.REPLACE,
                ContentType = metadata.ContentType,
            };
            copy.Headers.CacheControl = "private, no-store";
            await Internal.Value.CopyObjectAsync(copy);
            return finalKey;
        }
        catch (AmazonS3Exception error) when (error.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.PreconditionFailed)
        {
            throw new ApiError(409, "업로드한 파일이 없거나 변경되었습니다. 다시 업로드하세요.");
        }
    }

    internal sealed record UploadMetadata(string ContentType, string ETag);

    internal static UploadMetadata ValidateMetadata(long? size, string? contentType, string? etag, long maxSize, IReadOnlyList<string> allowedTypes)
    {
        if (size is not > 0) throw ApiError.Validation("파일 크기 메타데이터가 유효하지 않습니다.");
        if (size > maxSize) throw new ApiError(413, "파일 크기가 허용 한도를 초과합니다.");
        if (contentType is null || !allowedTypes.Contains(contentType) || !SupportedTypes.Contains(contentType))
            throw ApiError.Validation("허용되지 않는 파일 형식입니다.");
        if (string.IsNullOrEmpty(etag)) throw ApiError.Validation("파일 무결성 메타데이터가 유효하지 않습니다.");
        return new UploadMetadata(contentType, etag);
    }

    internal static bool OwnedKey(string? key, string prefix, long userId) =>
        key is not null && key.StartsWith($"{prefix}/{userId}/", StringComparison.Ordinal) && ProfileKey().IsMatch(key);

    static string Extension(string contentType) => contentType switch
    {
        "image/jpeg" => "jpg",
        "image/png" => "png",
        _ => "webp",
    };

    public static async Task DeleteObjectAsync(string? objectKey)
    {
        if (objectKey is null) return;
        if (!ProfileKey().IsMatch(objectKey)) throw new ArgumentException("invalid profile object key");
        try
        {
            await Internal.Value.DeleteObjectAsync(App.Config.S3Bucket, objectKey);
        }
        catch (Exception)
        {
            // Cleanup is best-effort; an orphaned object never becomes reachable again.
        }
    }
}
