defmodule CoworkUser.ObjectStoragePolicyTest do
  use ExUnit.Case, async: true
  alias CoworkUser.Storage.ObjectStorage

  test "missing, zero, malformed or negative object size is rejected" do
    for size <- ["", "0", "-1", "oops"] do
      assert {:error, {:validation, _}} =
               ObjectStorage.validate_metadata(
                 [{"content-length", size}, {"content-type", "image/png"}, {"etag", "version"}],
                 1024,
                 ["image/png"]
               )
    end
  end

  test "oversized objects cannot become a profile image" do
    assert {:error, {:payload_too_large, _}} =
             ObjectStorage.validate_metadata(
               [{"content-length", "1025"}, {"content-type", "image/png"}, {"etag", "version"}],
               1024,
               ["image/png"]
             )
  end

  test "disallowed content and missing integrity metadata cannot be confirmed" do
    for headers <- [
          [{"content-length", "100"}, {"content-type", "text/html"}, {"etag", "version"}],
          [{"content-length", "100"}, {"content-type", "image/png"}]
        ] do
      assert {:error, {:validation, _}} =
               ObjectStorage.validate_metadata(headers, 1024, ["image/png"])
    end
  end

  test "valid image metadata is preserved for immutable copy" do
    assert {:ok, %{size: 100, content_type: "image/png", etag: "version"}} =
             ObjectStorage.validate_metadata(
               [{"Content-Length", "100"}, {"Content-Type", "image/png"}, {"ETag", "version"}],
               1024,
               ["image/png"]
             )
  end
end
