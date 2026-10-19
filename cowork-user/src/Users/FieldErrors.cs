namespace CoworkUser.Users;

/// <summary>A changeset-style validation failure, formatted as "field message; field message".</summary>
public sealed class FieldErrors : Exception
{
    readonly SortedDictionary<string, List<string>> errors = new(StringComparer.Ordinal);

    public void Add(string field, string message)
    {
        if (!errors.TryGetValue(field, out var messages)) errors[field] = messages = [];
        messages.Add(message);
    }

    public bool Any => errors.Count > 0;

    public override string Message =>
        string.Join("; ", errors.Select(error => $"{error.Key} {string.Join(", ", error.Value)}"));

    public void ThrowIfAny()
    {
        if (Any) throw this;
    }
}
