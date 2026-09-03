# Read Mix's exported OTP coverage without loading or starting the application.
[input_path, output_path] = System.argv()
{:ok, _pid} = :cover.start()
:ok = :cover.import(String.to_charlist(input_path))
# An import-only process has no locally cover-compiled modules. Pass the import
# list explicitly instead of relying on analyse/2's implicit module selection.
{:result, results, failures} = :cover.analyse(:cover.imported_modules(), :coverage, :line)

if failures != [] do
  raise "Unable to analyse every exported module: #{inspect(failures)}"
end

# Match Mix's line-counting rules: line 0 is generated code, repeated entries
# for a module/line count once, and any covered entry makes that line covered.
lines =
  Enum.reduce(results, %{}, fn
    {{_module, 0}, _coverage}, acc ->
      acc

    {{module, line}, {covered, missed}}, acc
    when is_integer(line) and line > 0 and is_integer(covered) and covered >= 0 and
           is_integer(missed) and missed >= 0 ->
      Map.update(acc, {module, line}, covered > 0, &(&1 or covered > 0))

    entry, _acc ->
      raise "Unexpected OTP coverage entry: #{inspect(entry)}"
  end)

covered = Enum.count(lines, fn {_line, was_covered} -> was_covered end)
total = map_size(lines)

if total == 0 do
  raise "The export contains no executable application lines"
end

# No JSON dependency is required for these validated integer counts.
File.write!(output_path, "{\"covered\":#{covered},\"total\":#{total}}\n")
:ok = :cover.stop()
