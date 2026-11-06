// Reading follows the user's own rights in Confluence (the server calls Confluence with the user's credential). The group
// check is here; restricted spaces are closed by the forbid the platform generates from the classification of the type.

@id("confluence-read")
permit (
  principal in UserGroup::"mcp-confluence-read",
  action == Confluence::Action::"read",
  resource
)
when { resource is Confluence::Space || resource is Confluence::Page };
