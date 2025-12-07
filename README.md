# THIS IS ALPHA, DON'T USE

**PK**'**S** **P**ersonal **K**nowledge **M**anagement **S**ystem

A program that provides a single user API and command-line export tool for a zettlekasten style 
personal knowledge management directory.

## Build And Run

Make sure you have Maven and JDK 17 installed and running correctly. The following commands use the example directory in this repo (`test/data/example`), you can try it out on your own data by replacing that value with your own directory. For reference:

```text
test/data/example
├── Example.md
├── Notes
│   ├── Backlink-for-Resolved-Wikilink.md
│   ├── PKSPKMS.md
│   ├── Resolved Wikilink.md
│   └── Tasks
│       └── Task.md
├── README.md
└── Resources
    └── Neumann.jpg
```

```shell
# First, build pkspkms
git clone git@github.com:pskenny/pkspkms.git ~/pkspkms
cd pkspkms
mvn clean package
```

Try out exporting:

```shell
mkdir temp-dir
# Run export using test directory
java -jar target/pkspkms-0.1.0-ALPHA.jar export --directory test/data/example --query "" --output temp-dir --type "markdown"
```

Try out the server:

```shell
# Start server at port 23467 using test directory
java -jar target/pkspkms-0.1.0-ALPHA.jar server --directory test/data/example --port 23467
# In another terminal 
curl GET "http://localhost:23467/files/list" | jq .
```

Returns:

```json
{
  "resultSize": 6,
  "files": [
    {
      "title": "README",
      "tags": [
        "PKSPKMS",
        "Development",
        "Documentation"
      ],
      "filePath": "/README.md"
    },
    {
      "tags": [
        "Task"
      ],
      "filePath": "/Notes/Tasks/Task.md"
    },
    {
      "aliases": [
        "Resolved Wikilink Defined By YAML Frontmatter alias"
      ],
      "title": "Resolved Wikilink Defined By YAML Frontmatter title",
      "tags": [
        "Example"
      ],
      "filePath": "/Notes/Resolved Wikilink.md"
    },
    {
      "links": [
        "Resources/Neumann.jpg"
      ],
      "title": "Example Title",
      "tags": [
        "Example"
      ],
      "filePath": "/Example.md"
    },
    {
      "filePath": "/Resources/Neumann.jpg"
    },
    {
      "shouldBeANumber": 42,
      "filePath": "/Notes/PKSPKMS.md",
      "links": [
        "./Notes/Resolved Wikilink.md",
        "../Resources/Neumann.jpg"
      ],
      "title": "Different title than file name",
      "anotherYamlProperty": "test data",
      "content": "---\ntitle: Different title than file name\ntags:\n  - Meta\n  - PKSPKMS\n  - Tag\nanotherYamlProperty: \"test data\"\nshouldBeANumber: 42\n---\n\n## Links\n\n- [[Unresolved Wikilink]]\n- [Resolved Wikilink](./Notes/Resolved Wikilink.md)\n- [[Resolved Wikilink Defined By YAML Frontmatter title]]\n- [Resolved Wikilink Defined By YAML Frontmatter alias](./Notes/Resolved Wikilink.md)\n- [regular link](https://www.example.com)\n\n## Unordered List\n\n- First item\n- Second item\n- Third item\n\n## Ordered list\n\n1. First item\n2. Second item\n3. Third item\n\n## Code \n\n`code`\n\n```\nmore code\n```\n\n## Horizontal Rule \t\n\n---\n\n## Image\n\n![Relative image link](../Resources/Neumann.jpg)\n",                                                                                                                                                                
      "tags": [
        "Meta",
        "PKSPKMS",
        "Tag"
      ]
    }
  ]
}
```

You can also query it, such as `curl GET "http://localhost:23467/files/list?tags=PKSPKMS" | jq .` returns:

```json
{
  "resultSize": 2,
  "files": [
    {
      "title": "README",
      "tags": [
        "PKSPKMS",
        "Development",
        "Documentation"
      ],
      "filePath": "/README.md"
    },
    {
      "shouldBeANumber": 42,
      "filePath": "/Notes/PKSPKMS.md",
      "links": [
        "./Notes/Resolved Wikilink.md",
        "../Resources/Neumann.jpg"
      ],
      "title": "Different title than file name",
      "anotherYamlProperty": "test data",
      "content": "---\ntitle: Different title than file name\ntags:\n  - Meta\n  - PKSPKMS\n  - Tag\nanotherYamlProperty: \"test data\"\nshouldBeANumber: 42\n---\n\n## Links\n\n- [[Unresolved Wikilink]]\n- [Resolved Wikilink](./Notes/Resolved Wikilink.md)\n- [[Resolved Wikilink Defined By YAML Frontmatter title]]\n- [Resolved Wikilink Defined By YAML Frontmatter alias](./Notes/Resolved Wikilink.md)\n- [regular link](https://www.example.com)\n\n## Unordered List\n\n- First item\n- Second item\n- Third item\n\n## Ordered list\n\n1. First item\n2. Second item\n3. Third item\n\n## Code \n\n`code`\n\n```\nmore code\n```\n\n## Horizontal Rule \t\n\n---\n\n## Image\n\n![Relative image link](../Resources/Neumann.jpg)\n",                                                                                                                                                                
      "tags": [
        "Meta",
        "PKSPKMS",
        "Tag"
      ]
    }
  ]
}
```

### Testing

```shell
mvn clean test
```

## Known Issues

- Lots
- Doesn't support single line, comma separated YAML frontmatter properties (very relevant to tags)