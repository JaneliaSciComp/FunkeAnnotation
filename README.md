![example workflow](https://github.com/JaneliaSciComp/FunkeAnnotation/actions/workflows/maven.yml/badge.svg)

Small tool for annotating images.

**Installation:**
- download from [releases](https://github.com/JaneliaSciComp/FunkeAnnotation/releases) or build the tool using Maven `mvn clean package`
- copy the JAR file to the Fiji plugins directory
- the plugin will show up under Fiji > Plugins > Funke lab > Annotator ...

**Running in Phase 1:**
- upon start it will ask for a directory that has to contain directories 'A', 'B', 'M'
- each directory has to contain a series of png's [0...N].png

<img width="993" alt="Screenshot 2024-11-15 at 12 50 54 PM" src="https://github.com/user-attachments/assets/7e1f26e0-da60-4665-a350-19f252159b32">

**Running in Phase 2:**

- you need to additionally specify a JSON that contains the description of all features that can be annotated, e.g.:
```
{
    "DCV appearance": {
        "-1": "One or more DCV has disappeared in B",
        "0": "The same number of DCVs are present in B as in A",
        "1": "One or more DCV has appeared in B"
    }, 
    "DCV size": {
        "-1": "One or more DCVs in B are smaller than in A",
        "0": "One or more DCVs in B are the same size as in A",
        "1": "One or more DCVs in B are larger than in A"
    }
}
```
<img width="1151" alt="Screenshot 2025-02-28 at 2 18 57 PM" src="https://github.com/user-attachments/assets/0357d57d-929f-4aa9-88ce-41f12ad3e5d9" />
