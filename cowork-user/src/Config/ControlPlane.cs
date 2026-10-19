using System.Net.Http.Headers;
using System.Text;

namespace CoworkUser.Config;

public static class ControlPlane
{
    public static HttpClient Client() =>
        new(new SocketsHttpHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(15) };

    public static HttpRequestMessage Request(HttpMethod method, string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var endpoint) || endpoint.UserInfo != ""
            || endpoint.Host == "" || endpoint.Scheme is not ("http" or "https"))
            throw new InvalidOperationException("Use a Config/Eureka HTTP(S) URL without credentials");

        var profile = Environment.GetEnvironmentVariable("APP_CONFIG_PROFILE") is { Length: > 0 } p ? p : "local";
        if (profile == "prod" && endpoint.Scheme != "https" && !PrivateIpv4(endpoint.Host))
            throw new InvalidOperationException("Use HTTPS or a private IPv4 HTTP URL for production Config/Eureka");

        var username = Environment.GetEnvironmentVariable("CONFIG_CLIENT_USERNAME") ?? throw new InvalidOperationException("CONFIG_CLIENT_USERNAME is not set");
        var password = Environment.GetEnvironmentVariable("CONFIG_CLIENT_PASSWORD") ?? throw new InvalidOperationException("CONFIG_CLIENT_PASSWORD is not set");
        if (username == "" || password == "") throw new InvalidOperationException("Provide Config/Eureka bootstrap credentials");

        var request = new HttpRequestMessage(method, endpoint);
        request.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes($"{username}:{password}")));
        return request;
    }

    // Only RFC1918 IPv4 literals: a host name would let DNS redirect plaintext credentials.
    internal static bool PrivateIpv4(string host)
    {
        var parts = host.Split('.');
        if (parts.Length != 4 || !parts.All(part => part.Length is > 0 and <= 3 && part.All(char.IsAsciiDigit) && int.Parse(part) <= 255))
            return false;
        var (first, second) = (int.Parse(parts[0]), int.Parse(parts[1]));
        return first == 10 || (first == 172 && second is >= 16 and <= 31) || (first == 192 && second == 168);
    }
}
