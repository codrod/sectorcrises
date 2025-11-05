# Overview

"Sector Crises" is a mod for the popular indie game "Starsector" made by [Fractal Softworks](https://fractalsoftworks.com/) which adds late game and mid game challenges which affect the entire in-game universe (sector). This mod is spiritually inspired by the endgame crises from Paradox Interactive's [Stellaris](https://www.paradoxinteractive.com/games/stellaris/about).

# Contributing

Contributions are welcome just follow the [Development Environment Setup](#development-environment-setup) section below and then submit a pull-request to the repository when finished. No need to get into contact with me first but I may have some questions so checking for messages on Github is helpful. Also I can't guarantee I will notice or respond to the pull-request in a timely manner.

# Development Environment Setup

This section will walk you through the process of setting up [VS Code](https://code.visualstudio.com/) to begin development on the repository. Note that you don't have to use VS Code but these instructions only cover the setup process for VS Code; though any IDE with Maven support should work.

1. Install the following VS Code extensions:
    * [Language support for Java for Visual Studio Code](https://marketplace.visualstudio.com/items?itemName=redhat.java)
    * [Debugger for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-debug)
    * [Maven for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-maven)
2. Clone the repository into to the starsector/mods folder
3. Copy the necessary JAR dependencies from the starsector/* root folder to the lib/ folder
    * Not all of the JARs are needed you can see which ones in the pom.xml and build the project to test if all dependencies are loaded

## Building

This project uses a standard Maven build process so basic familiarity with [Apache Maven](https://maven.apache.org/) is helpful.

1. The final build artifact for the project is a JAR file so just run the Maven "package" command to build the project
    * The project is setup to output the built JAR into the jars/ folder and Starsector will immediately load the new changes at the next startup

## Debugging

1. Starsector must be setup to launch in [debugging mode](https://www.google.com/search?q=java+debugging+mode) first
    * Add the following options to the Starsector launch parameters:
        * -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
        * -Dorg.codehaus.janino.source_debugging.enable=true
    * It is recommended that you create a separate debugging launcher (i.e. starsector_debug.sh) for ease of use
2. Start the Starsector launcher
    * You can launch from the terminal to confirm that debugging mode is enabled just look for the following text in the launchers output (around the top):
        * Listening for transport dt_socket at address: 5005
3. In VS Code select Run -> Start Debugging to launch the debugger
    * It will ask you to select the process to attach to for debugging and most likely Starsector is the only debuggable Java process running on your machine

## Debugging (Optional)

Starsector includes a public API for modders and the source code for this public API is bundled with the release files. It is recommended but not required to copy the public API source files into the project folder to make debugging and code navigation easier.

1. Extract the starsector/starfarer.api.zip file into the src/java/main folder
    * These files are complied for better code navigation support but are not included in the final JAR so any changes made to them won't take effect
    * None of these files are tracked in source control; they are just used as reference and for setting break-points

# Future Ideas:

This section is just to keep track of potential features/changes to be implemented in the future.

* Remnant Crisis
    * Make remnants loiter/ambush in hyperspace (based on Pather behaviors)
    * Make remnants patrol in hyperspace like normal systems
    * Remnant fleets in nexus systems should get access to sustained burned drive (etc.) to make them more lethal
    * Include Omega ships in the attack fleets

* Colony Crisis
    * Possibly add a factor which freezes colony crisis monthly progress while a sector crisis is active