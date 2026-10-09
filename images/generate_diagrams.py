#!/usr/bin/env python3
"""Regenerate the README's editable SVG schematics; Python standard library only.

This is documentation tooling, independent of the Ant runtime build.
Coordinates are presentation choices; workflow names/guards come from the JSON
models named in images/README.md. Render and inspect after changing this file.
"""
from pathlib import Path
from html import escape
import json

ROOT = Path(__file__).resolve().parent
INK = '#1b2b40'
BLUE = '#24598e'
PURPLE = '#7350a2'
TEAL = '#19776e'

class Diagram:
    def __init__(self, width, height, title, description):
        self.width, self.height = width, height
        self.parts = [f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc">
<title id="title">{escape(title)}</title><desc id="desc">{escape(description)}</desc>
<defs>''']
        for key, colour in [('flow', BLUE), ('rule', PURPLE), ('observe', TEAL), ('ack', '#65758b')]:
            self.parts.append(f'<marker id="{key}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="{colour}"/></marker>')
        self.parts.append('</defs><rect width="100%" height="100%" fill="#ffffff"/>')
    def text(self, x, y, lines, size=17, bold=False, anchor='middle', colour=INK):
        if isinstance(lines, str): lines=[lines]
        weight='600' if bold else '400'
        for i, line in enumerate(lines):
            self.parts.append(f'<text x="{x}" y="{y+i*(size+7)}" font-family="Arial, Helvetica, sans-serif" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{colour}">{escape(line)}</text>')
    def box(self, x,y,w,h,title,lines=(),kind='control',identity=None):
        fills={'control':'#edf3fa','business':'#edf7f0','config':'#f3eff8','endpoint':'#f5f6f8','observe':'#edf7f5'}
        node_id=f' id="{escape(identity)}"' if identity else ''
        self.parts.append(f'<rect{node_id} x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fills[kind]}" stroke="#60738a" stroke-width="1.5"/>')
        self.text(x+w/2,y+29,title,19,True)
        self.text(x+w/2,y+55,lines,15)
    def path(self, d, kind='flow', source=None, target=None):
        colour={'flow':BLUE,'publication':BLUE,'rule':PURPLE,'observe':TEAL,'ack':'#65758b'}[kind]
        dash={'flow':'','publication':' stroke-dasharray="6 4"','rule':' stroke-dasharray="8 5"','observe':' stroke-dasharray="3 5"','ack':' stroke-dasharray="4 4"'}[kind]
        identity=f' data-source="{escape(source)}" data-target="{escape(target)}"' if source and target else ''
        marker='flow' if kind=='publication' else kind
        self.parts.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2"{dash}{identity} marker-end="url(#{marker})"/>')
    def place(self, x, y, identity, token=False):
        self.parts.append(f'<circle id="{identity}" cx="{x}" cy="{y}" r="27" fill="#edf7f0" stroke="{INK}" stroke-width="2"/>')
        self.text(x,y-3 if token else y+6,identity,18,True)
        if token: self.parts.append(f'<circle cx="{x}" cy="{y+12}" r="4" fill="{INK}"/>')
    def transition(self, x, y, identity, terminal=False, join=False):
        width=28 if join else 16
        self.parts.append(f'<rect id="{identity}" x="{x-width/2:g}" y="{y-24}" width="{width}" height="48" fill="{INK if terminal else BLUE}"/>')
        if join: self.text(x,y+4,'AND',10,True,colour='#ffffff')
        self.text(x,y+48,identity,13)
    def group(self,x,y,w,h,label):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="#fafbfc" stroke="#98a7b6" stroke-width="1.5"/>')
        self.text(x+18,y+27,label,17,True,'start')
    def save(self,name):
        (ROOT/name).write_text('\n'.join(self.parts)+ '\n</svg>\n')

# Opening image: the drawn Petri net becomes a set of independent hosts exchanging token messages.
d=Diagram(1000,470,'The Petri net you draw is the system that runs','Left: a four-place Petri net model in which P1 forks to P2 and P3, which join before P4. A purple dashed arrow labelled deploy local rules leads to the right, where the same net runs as four separate hosts. Each host contains its own input transition, place function and output transition; a place can be any service. Tokens travel between hosts as messages along blue arrows, and P4\'s input transition waits for both P2 and P3. No central engine routes the tokens. Teal dotted lines carry observations from every host to Monitor, outside the token path, for complete Petri-net analysis.')
d.parts.append('<defs><filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="2" stdDeviation="2.5" flood-color="#1f3b57" flood-opacity="0.18"/></filter></defs>')
d.parts.append(f'''<!-- Headline -->
<text x="500" y="44" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="26" font-weight="700" fill="{INK}">The Petri net you draw is the system that runs</text>
<text x="500" y="72" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="15" fill="#4a5d70">A place can be any service · transitions become local rules · tokens become messages</text>
<!-- LEFT: the model -->
<rect x="24" y="100" width="300" height="300" rx="14" fill="#f4f7fb" stroke="#c9d6e3"/>
<text x="174" y="126" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="14" font-weight="700" fill="{INK}" letter-spacing="1">1 · DRAW THE MODEL</text>
<text x="174" y="145" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="12" font-style="italic" fill="#4a5d70">in ProcessEditor</text>
<g font-family="Helvetica, Arial, sans-serif" font-size="13" fill="{INK}" text-anchor="middle">
  <!-- P1 -->
  <rect x="44" y="232" width="7" height="34" fill="{INK}"/>
  <line x1="51" y1="249" x2="66" y2="249" stroke="{INK}" stroke-width="1.6"/>
  <circle cx="82" cy="249" r="16" fill="#fff" stroke="{INK}" stroke-width="2"/>
  <circle cx="82" cy="249" r="5" fill="#e8822a"/>
  <text x="82" y="282">P1</text>
  <line x1="98" y1="249" x2="110" y2="249" stroke="{INK}" stroke-width="1.6"/>
  <rect x="110" y="232" width="7" height="34" fill="{INK}"/>
  <!-- fork -->
  <path d="M117,243 C140,243 140,190 160,190" fill="none" stroke="{BLUE}" stroke-width="1.6" stroke-dasharray="5 3" marker-end="url(#flow)"/>
  <path d="M117,255 C140,255 140,308 160,308" fill="none" stroke="{BLUE}" stroke-width="1.6" stroke-dasharray="5 3" marker-end="url(#flow)"/>
  <!-- P2 -->
  <rect x="162" y="173" width="7" height="34" fill="{INK}"/>
  <line x1="169" y1="190" x2="180" y2="190" stroke="{INK}" stroke-width="1.6"/>
  <circle cx="196" cy="190" r="16" fill="#fff" stroke="{INK}" stroke-width="2"/>
  <text x="196" y="160">P2</text>
  <line x1="212" y1="190" x2="222" y2="190" stroke="{INK}" stroke-width="1.6"/>
  <rect x="222" y="173" width="7" height="34" fill="{INK}"/>
  <!-- P3 -->
  <rect x="162" y="291" width="7" height="34" fill="{INK}"/>
  <line x1="169" y1="308" x2="180" y2="308" stroke="{INK}" stroke-width="1.6"/>
  <circle cx="196" cy="308" r="16" fill="#fff" stroke="{INK}" stroke-width="2"/>
  <text x="196" y="345">P3</text>
  <line x1="212" y1="308" x2="222" y2="308" stroke="{INK}" stroke-width="1.6"/>
  <rect x="222" y="291" width="7" height="34" fill="{INK}"/>
  <!-- join -->
  <path d="M229,190 C250,190 250,243 266,243" fill="none" stroke="{BLUE}" stroke-width="1.6" stroke-dasharray="5 3" marker-end="url(#flow)"/>
  <path d="M229,308 C250,308 250,255 266,255" fill="none" stroke="{BLUE}" stroke-width="1.6" stroke-dasharray="5 3" marker-end="url(#flow)"/>
  <rect x="268" y="232" width="7" height="34" fill="{INK}"/>
  <text x="271" y="226" font-size="10" fill="#4a5d70">AND</text>
  <line x1="275" y1="249" x2="284" y2="249" stroke="{INK}" stroke-width="1.6"/>
  <circle cx="300" cy="249" r="14" fill="#fff" stroke="{INK}" stroke-width="2"/>
  <text x="300" y="282">P4</text>
</g>
<text x="174" y="384" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="12.5" fill="#4a5d70">fork to P2 and P3 · join before P4</text>
<!-- deploy arrow -->
<line x1="332" y1="250" x2="398" y2="250" stroke="{PURPLE}" stroke-width="2.4" stroke-dasharray="7 4" marker-end="url(#rule)"/>
<text x="365" y="236" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="12" font-weight="700" fill="{PURPLE}">deploy</text>
<text x="365" y="272" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="11" fill="{PURPLE}">local rules</text>
<text x="365" y="290" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="10.5" font-style="italic" fill="#4a5d70">via Ant launcher</text>
<!-- RIGHT: running hosts -->
<rect x="406" y="100" width="570" height="300" rx="14" fill="#f7fbf9" stroke="#c9dccf"/>
<text x="691" y="126" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="14" font-weight="700" fill="{INK}" letter-spacing="1">2 · RUN IT AS SEPARATE HOSTS</text>
<g font-family="Helvetica, Arial, sans-serif" text-anchor="middle">
  <!-- host template: rect + unit (bar circle bar) + labels -->
  <!-- Host P1 -->
  <g filter="url(#shadow)"><rect x="424" y="212" width="132" height="76" rx="10" fill="#fff" stroke="{BLUE}" stroke-width="1.6"/></g>
  <text x="490" y="231" font-size="12" font-weight="700" fill="{INK}">Host P1</text>
  <rect x="448" y="244" width="6" height="28" fill="{INK}"/><circle cx="490" cy="258" r="13" fill="#fff" stroke="{INK}" stroke-width="1.8"/><rect x="526" y="244" width="6" height="28" fill="{INK}"/>
  <line x1="454" y1="258" x2="477" y2="258" stroke="{INK}" stroke-width="1.3"/><line x1="503" y1="258" x2="526" y2="258" stroke="{INK}" stroke-width="1.3"/>
  <text x="490" y="282" font-size="9.5" fill="#4a5d70">T_in · P · T_out</text>
  <!-- Host P2 -->
  <g filter="url(#shadow)"><rect x="618" y="146" width="132" height="76" rx="10" fill="#fff" stroke="{BLUE}" stroke-width="1.6"/></g>
  <text x="684" y="165" font-size="12" font-weight="700" fill="{INK}">Host P2</text>
  <rect x="642" y="178" width="6" height="28" fill="{INK}"/><circle cx="684" cy="192" r="13" fill="#fff" stroke="{INK}" stroke-width="1.8"/><rect x="720" y="178" width="6" height="28" fill="{INK}"/>
  <line x1="648" y1="192" x2="671" y2="192" stroke="{INK}" stroke-width="1.3"/><line x1="697" y1="192" x2="720" y2="192" stroke="{INK}" stroke-width="1.3"/>
  <text x="684" y="216" font-size="9.5" fill="#4a5d70">T_in · P · T_out</text>
  <!-- Host P3 -->
  <g filter="url(#shadow)"><rect x="618" y="278" width="132" height="76" rx="10" fill="#fff" stroke="{BLUE}" stroke-width="1.6"/></g>
  <text x="684" y="297" font-size="12" font-weight="700" fill="{INK}">Host P3</text>
  <rect x="642" y="310" width="6" height="28" fill="{INK}"/><circle cx="684" cy="324" r="13" fill="#fff" stroke="{INK}" stroke-width="1.8"/><rect x="720" y="310" width="6" height="28" fill="{INK}"/>
  <line x1="648" y1="324" x2="671" y2="324" stroke="{INK}" stroke-width="1.3"/><line x1="697" y1="324" x2="720" y2="324" stroke="{INK}" stroke-width="1.3"/>
  <text x="684" y="348" font-size="9.5" fill="#4a5d70">T_in · P · T_out</text>
  <!-- Host P4 -->
  <g filter="url(#shadow)"><rect x="826" y="212" width="132" height="76" rx="10" fill="#fff" stroke="{BLUE}" stroke-width="1.6"/></g>
  <text x="892" y="231" font-size="12" font-weight="700" fill="{INK}">Host P4</text>
  <rect x="850" y="244" width="6" height="28" fill="{INK}"/><circle cx="892" cy="258" r="13" fill="#fff" stroke="{INK}" stroke-width="1.8"/><rect x="928" y="244" width="6" height="28" fill="{INK}"/>
  <line x1="856" y1="258" x2="879" y2="258" stroke="{INK}" stroke-width="1.3"/><line x1="905" y1="258" x2="928" y2="258" stroke="{INK}" stroke-width="1.3"/>
  <text x="892" y="282" font-size="9.5" fill="#4a5d70">waits for both inputs</text>
</g>
<!-- network messages (token flow) -->
<path d="M556,238 C585,238 590,184 616,184" fill="none" stroke="{BLUE}" stroke-width="2" marker-end="url(#flow)"/>
<path d="M556,262 C585,262 590,316 616,316" fill="none" stroke="{BLUE}" stroke-width="2" marker-end="url(#flow)"/>
<path d="M750,184 C790,184 795,238 824,238" fill="none" stroke="{BLUE}" stroke-width="2" marker-end="url(#flow)"/>
<path d="M750,316 C790,316 795,262 824,262" fill="none" stroke="{BLUE}" stroke-width="2" marker-end="url(#flow)"/>
<!-- tokens in flight -->
<g stroke="#ffffff" stroke-width="1.5">
  <circle cx="588" cy="208" r="6.5" fill="#e8822a"/>
  <circle cx="588" cy="291" r="6.5" fill="#e8822a"/>
  <circle cx="790" cy="214" r="6.5" fill="#e8822a"/>
</g>
<text x="598" y="252" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="10.5" fill="{BLUE}">tokens</text>
<text x="598" y="265" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="10.5" fill="{BLUE}">as messages</text>
<text x="788" y="254" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="10.5" fill="{BLUE}">no central</text>
<text x="788" y="267" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="10.5" fill="{BLUE}">engine</text>
<!-- Monitor -->
<rect x="560" y="372" width="260" height="24" rx="12" fill="#e7f5f2" stroke="{TEAL}"/>
<text x="690" y="389" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="12" font-weight="700" fill="{TEAL}">Monitor · complete Petri-net analysis</text>
<g fill="none" stroke="{TEAL}" stroke-width="1.4" stroke-dasharray="2 4">
  <path d="M490,288 C490,330 530,384 558,384" marker-end="url(#observe)"/>
  <path d="M684,354 L684,370" marker-end="url(#observe)"/>
  <path d="M892,288 C892,330 850,384 822,384" marker-end="url(#observe)"/>
</g>
<!-- Footer legend -->
<g font-family="Helvetica, Arial, sans-serif" font-size="12" fill="#4a5d70">
  <line x1="190" y1="440" x2="222" y2="440" stroke="{BLUE}" stroke-width="2" marker-end="url(#flow)"/><text x="230" y="444">token message</text>
  <line x1="350" y1="440" x2="382" y2="440" stroke="{PURPLE}" stroke-width="2" stroke-dasharray="6 3" marker-end="url(#rule)"/><text x="390" y="444">rule installation</text>
  <line x1="525" y1="440" x2="557" y2="440" stroke="{TEAL}" stroke-width="1.4" stroke-dasharray="2 4" marker-end="url(#observe)"/><text x="565" y="444">observation</text>
  <circle cx="680" cy="440" r="6" fill="#e8822a"/><text x="692" y="444">token</text>
</g>''')
d.save('rpso-overview.svg')

# Logical sequence for one invocation, not a Java call-stack or timing trace.
d=Diagram(900,920,'RPSO execution sequence','Three logical lifelines within one generic host: input transition T_in, place function P, and output transition T_out. T_in receives and buffers a token, synchronizes the required inputs, and invokes P with arguments. P performs its function or calls the bound service, then returns the result. T_in hands the result to T_out and ends its activation. T_out applies routing rules, publishes tokens or records termination, then ends its activation. The activation bars are light blue; their lengths are conceptual, not measured durations.')
for x,role,name in [(160,'T_in','Input transition'),(450,'P','Place function'),(740,'T_out','Output transition')]:
    d.box(x-105,20,210,70,role,[name])
    d.parts.append(f'<line x1="{x}" y1="90" x2="{x}" y2="865" stroke="#98a7b6" stroke-width="1.5" stroke-dasharray="5 5"/>')
for x,top,bottom in [(160,105,535),(450,300,460),(740,535,835)]:
    d.parts.append(f'<rect x="{x-9}" y="{top}" width="18" height="{bottom-top}" fill="#e1edf9" stroke="{BLUE}" stroke-width="1.5"/>')
def sequence_note(x,y,w,lines):
    height=len(lines)*24+18
    d.parts.append(f'<rect x="{x-w/2}" y="{y}" width="{w}" height="{height}" fill="#f5f6f8" stroke="#98a7b6" stroke-width="1.2"/>')
    d.text(x,y+26,lines,17)
def sequence_message(source,target,y,label,returning=False):
    start=source-9 if source>target else source+9
    end=target+9 if source>target else target-9
    dash=' stroke-dasharray="6 4"' if returning else ''
    d.parts.append(f'<path d="M {start} {y} L {end} {y}" fill="none" stroke="{BLUE}" stroke-width="2"{dash} marker-end="url(#flow)"/>')
    d.text((source+target)/2,y-16,label,17)
sequence_note(160,120,200,['Receive and buffer','token'])
sequence_note(160,195,230,['Synchronize required','inputs'])
sequence_message(160,450,300,'Invoke with arguments')
sequence_note(450,345,210,['Perform function','or call bound service'])
sequence_message(450,160,460,'Return result',returning=True)
sequence_message(160,740,535,'Hand off result')
sequence_note(740,575,210,['Apply routing rules'])
d.parts.append('<rect x="50" y="655" width="800" height="190" fill="none" stroke="#98a7b6" stroke-width="1.5"/>')
d.text(70,683,'alt: continue workflow',17,True,'start')
d.text(70,719,'Publish token or fork children',17,anchor='start')
d.path('M 749 705 L 800 705 L 800 733 L 749 733')
d.parts.append('<line x1="50" y1="750" x2="850" y2="750" stroke="#98a7b6" stroke-width="1.5" stroke-dasharray="5 5"/>')
d.text(70,778,'OR end workflow',17,True,'start')
d.text(70,814,'Record termination',17,anchor='start')
d.path('M 749 798 L 800 798 L 800 826 L 749 826')
d.text(450,898,'Shaded bars: activation for one invocation · Vertical order: sequence, not measured time',16)
d.save('rpso-execution-sequence.svg')

# Responsibility boundaries and the current local business-service call.
d=Diagram(1120,690,'RPSO responsibility boundaries','Process and deployment definitions install local rules in a generic orchestration host. The host implements input transition, place invocation and output transition roles, invokes a separately packaged domain or token operation in-process, routes publications to other hosts, and supplies collected observations to Monitor outside the token path.')
d.group(235,145,650,370,'Generic numbered host · P1–P6')
d.box(355,20,410,90,'Process and deployment definitions',['Topology · contracts · version · bindings'],'config')
d.box(405,195,310,70,'Installed local RuleBase',kind='config')
d.box(265,300,165,90,'T_in',['Buffer / synchronize'])
d.box(475,300,175,90,'Place P',['Invoke bound operation'])
d.box(695,300,160,90,'T_out',['Route / publish'])
d.box(450,440,255,65,'Bound service JAR',['Domain or token operation'],'business')
d.box(20,300,175,90,'Incoming token',['Generator / prior host'],'endpoint')
d.box(935,300,165,90,'Outcome',['Publish to next host','or terminate locally'],'endpoint')
d.box(390,565,350,75,'Monitor',['Collection · reconstruction · diagrams'],'observe')
d.path('M 560 110 L 560 195','rule')
d.text(573,138,'compile / deploy',14,anchor='start',colour=PURPLE)
d.path('M 435 265 L 435 282 L 347 282 L 347 300','rule')
d.path('M 685 265 L 685 282 L 775 282 L 775 300','rule')
d.path('M 195 345 L 265 345')
d.path('M 430 345 L 475 345')
d.path('M 650 345 L 695 345')
d.path('M 855 345 L 935 345')
d.path('M 510 390 L 510 440')
d.path('M 635 440 L 635 390')
d.text(490,419,'named inputs',13,anchor='end')
d.text(652,414,['result +','routing symbol'],13,anchor='start')
d.path('M 560 515 L 560 565','observe')
d.text(577,545,'collector observations',14,anchor='start',colour=TEAL)
d.text(560,673,'Solid: execution / token flow     Dashed: rule installation     Dotted: observation',15)
d.save('rpso-architecture.svg')

# Preparation, acknowledged local installation and runtime message flow.
d=Diagram(1120,760,'Rule deployment and runtime token flow','Workflow JSON, canonical service contracts and deployment bindings feed rule generation. RuleDeployer sends versioned local fragments to hosts and waits for acknowledgements in the normal deployment path. Event generation and host-to-host token flow are separate from rule installation.')
d.box(25,25,330,80,'Workflow JSON',['Places · transitions · guards'],'config')
d.box(395,25,330,80,'Service contracts',['Named inputs · output · operation'],'config')
d.box(765,25,330,80,'Deployment profile',['Implementation · host · channel'],'config')
d.box(375,200,370,85,'Binding and rule generation',['TopologyBindingGenerator · RuleDeployer'],'config')
d.box(375,350,370,85,'Fragment distribution',['Versioned RuleML · local commitments'],'config')
d.box(45,510,345,90,'Generic host A',['Installed rules · T_in → P → T_out'])
d.box(735,510,345,90,'Generic host B',['Installed rules · T_in → P → T_out'])
d.box(405,660,310,70,'Event generator',['Start workflow tokens'],'endpoint')
d.path('M 190 105 L 190 158 L 465 158 L 465 200','rule')
d.path('M 560 105 L 560 200','rule')
d.path('M 930 105 L 930 158 L 655 158 L 655 200','rule')
d.path('M 560 285 L 560 350','rule')
d.path('M 460 435 L 460 460 L 218 460 L 218 510','rule')
d.path('M 660 435 L 660 460 L 908 460 L 908 510','rule')
d.path('M 135 510 L 135 320 L 375 320 L 375 388','ack')
d.path('M 990 510 L 990 320 L 745 320 L 745 388','ack')
d.text(153,338,'acknowledgement',14,anchor='start',colour='#65758b')
d.text(972,338,'acknowledgement',14,anchor='end',colour='#65758b')
d.path('M 390 555 L 735 555')
d.text(562,543,'runtime token publication',15)
d.path('M 455 660 L 455 630 L 217 630 L 217 600')
d.text(562,483,'Rules are installed before the normal workflow start',16)
d.text(560,750,'Local acknowledgements are not a global atomic-commit protocol.',15)
d.save('rpso-rule-deployment.svg')

# Financial logical activity schematic.
d=Diagram(1120,505,'Financial loan-application workflow','Submitted applications reach Validation on P1. Valid results fork to Credit Check on P2 and Fraud Check on P3. Underwriting on P4 joins both named results. Approved and conditional outcomes proceed to Decision on P5; invalid and declined applications terminate early. Monitor is not a business activity. The top-right legend expands a rounded Credit Check activity into T_in, its service function at P, and T_out.')
d.box(20,200,180,90,'Validation',['P1 · validationResults'],'business')
d.box(290,70,210,90,'Credit Check',['P2 · creditCheckResults'],'business')
d.box(290,330,210,90,'Fraud Check',['P3 · fraudCheckResults'],'business')
d.box(640,200,205,90,'Underwriting',['P4 · joins both results'],'business')
d.box(915,200,185,90,'Decision',['P5 · final outcome'],'business')
d.box(20,50,180,65,'Submitted',kind='endpoint')
d.box(20,370,180,65,'Invalid: terminate',kind='endpoint')
d.box(640,370,205,65,'Declined: terminate',kind='endpoint')
d.box(915,370,185,65,'Complete',kind='endpoint')
d.path('M 110 115 L 110 200')
d.path('M 200 245 L 290 115')
d.path('M 200 245 L 290 375')
d.text(234,243,['valid:','fork'],14)
d.path('M 110 290 L 110 370')
d.text(125,338,'invalid',14,anchor='start')
d.path('M 500 115 L 640 245')
d.path('M 500 375 L 640 245')
d.text(570,243,['join both','inputs'],14)
d.path('M 845 245 L 915 245')
d.text(880,204,['approved /','conditional'],14)
d.path('M 742 290 L 742 370')
d.text(757,338,'declined',14,anchor='start')
d.path('M 1007 290 L 1007 370')
# Explain the change from explicit Petri-net units to named activity boxes.
# Keep the example inside a labelled legend, clear of the workflow's routes.
d.parts.append('<g id="activity-notation-legend" aria-label="Legend: each rounded service activity represents T_in, a service function at P, and T_out.">')
d.text(995,52,'Legend: inside an activity',14,True)
# Match the actual Credit Check activity's 210 x 90 box.
d.parts.append('<rect x="890" y="70" width="210" height="90" rx="8" fill="#edf7f0" stroke="#60738a" stroke-width="1.5"/>')
d.text(995,99,'Credit Check',19,True)
d.parts.append(f'<rect x="913" y="112" width="8" height="24" fill="{BLUE}"/>')
d.parts.append(f'<circle cx="995" cy="124" r="13" fill="#edf7f0" stroke="{INK}" stroke-width="2"/>')
d.text(995,129,'P',14,True)
d.parts.append(f'<rect x="1069" y="112" width="8" height="24" fill="{BLUE}"/>')
for start, end in [(921,982),(1008,1069)]:
    d.parts.append(f'<path d="M {start} 124 L {end-6} 124" fill="none" stroke="{BLUE}" stroke-width="1.5"/>')
    d.parts.append(f'<path d="M {end-6} 121 L {end} 124 L {end-6} 127 Z" fill="{BLUE}"/>')
d.text(917,151,'T_in',11)
d.text(995,151,'service function',11)
d.text(1073,151,'T_out',11)
d.parts.append('</g>')
d.text(560,482,'Activity boxes abbreviate local T_in → P → T_out roles; arrows show logical publication.',15)
d.save('financial-workflow.svg')

# Clinical model: three named inputs at Diagnosis, OR merge at Treatment.
d=Diagram(1120,600,'Emergency department patient workflow','Triage on P1 chooses direct treatment or a three-way diagnostic fork. Laboratory on P2, Cardiology on P3 and Radiology on P4 feed a synchronization join at Diagnosis on P5. Treatment on P6 accepts either the diagnosis path or direct triage, then terminates the patient workflow. Monitor observes separately.')
d.box(20,300,135,75,'Patient arrival',kind='endpoint')
d.box(205,290,165,100,'Triage',['P1 · select path'],'business')
d.box(450,155,165,100,'Laboratory',['P2'],'business')
d.box(450,290,165,100,'Cardiology',['P3'],'business')
d.box(450,425,165,100,'Radiology',['P4'],'business')
d.box(745,290,165,100,'Diagnosis',['P5 · join all three'],'business')
d.box(950,290,150,100,'Treatment',['P6 · either input'],'business')
d.box(950,480,150,65,'Terminate',kind='endpoint')
d.path('M 155 338 L 205 338')
d.path('M 370 340 L 450 205')
d.path('M 370 340 L 450 340')
d.path('M 370 340 L 450 475')
d.text(405,325,'fork',14)
d.text(285,442,['STANDARD_WORKFLOW','parallel diagnostics'],14)
d.path('M 615 205 L 745 340')
d.path('M 615 340 L 745 340')
d.path('M 615 475 L 745 340')
d.text(680,302,['all 3','results'],14)
d.path('M 910 340 L 950 340')
d.path('M 285 290 L 285 90 L 1025 90 L 1025 290')
d.text(655,74,'DIRECT_TO_TREATMENT · executeDirectTreatment',16)
d.path('M 1025 390 L 1025 480')
d.text(560,576,'Treatment merges alternatives; Diagnosis synchronizes all diagnostic inputs. Monitor is outside this path.',15)
d.save('healthcare-workflow.svg')

# Boolean-function examples: exact model identities, independent of layout.
def load_model(filename):
    model=json.loads((ROOT.parent/'btsn.common/ProcessDefinitionFolder/petrinet/Workflow'/filename).read_text())
    return ({node['id']:node for node in model['elements']},
            {(arc['source'],arc['target']):arc for arc in model['arrows']})

def arc_drawer(diagram, arcs):
    drawn=set()
    def draw(source,target,path,publication=False):
        assert (source,target) in arcs, f'Unknown model arc: {source} → {target}'
        drawn.add((source,target))
        diagram.path(path,'publication' if publication else 'flow',source,target)
    return draw, drawn

nodes,arcs=load_model('P1_Tutorial_Workflow.json')
d=Diagram(1120,465,'One place: Boolean functionality and transition coordination','P1 is a circular place between input and output transition bars. Its bound function produces true or false independently of the arriving Boolean value. The output transition routes true to termination and false back to the input. The generic host implements the local unit; Monitor observes separately. The dot illustrates a token, not a captured marking.')
draw,drawn=arc_drawer(d,arcs)
d.group(255,55,530,255,'Generic host · T_in → P → T_out')
d.box(20,157,210,85,'Generator',['Workflow tokens'],'endpoint',identity='P1_EVENTGENERATOR')
d.transition(350,200,'T_in_P1')
d.place(520,200,'P1',token=True)
d.transition(690,200,'T_out_P1')
d.transition(950,200,'Terminate',terminal=True)
d.text(350,280,'Receive / buffer',15)
d.text(520,280,'Function: true / false',15,True)
d.text(690,280,'Route result',15)
d.text(950,280,'End workflow',15,True)
draw('P1_EVENTGENERATOR','T_in_P1','M 230 200 L 342 200',True)
draw('T_in_P1','P1','M 358 200 L 493 200')
draw('P1','T_out_P1','M 547 200 L 682 200')
draw('T_out_P1','Terminate','M 698 200 L 942 200',True)
draw('T_out_P1','T_in_P1','M 690 176 L 690 115 L 350 115 L 350 176',True)
assert drawn==set(arcs)
assert arcs['T_out_P1','Terminate']['decision_value']=='true'
assert arcs['T_out_P1','T_in_P1']['decision_value']=='false'
d.text(820,185,'true',15)
d.text(520,105,'false: invoke the function again',15)
d.box(365,350,390,70,'Monitor',['Collected execution observations'],'observe')
d.path('M 520 310 L 520 350','observe')
d.text(537,336,'collection',14,anchor='start',colour=TEAL)
d.text(560,448,'Circle: place · Bar: transition · Dot: illustrative token · Dashed blue: publication',14)
d.save('p1-tutorial.svg')

nodes,arcs=load_model('P1_P2_P3_P4_Fork_Join_Workflow.json')
d=Diagram(1120,700,'Four-place Boolean-function model with fork and input join','Each of P1 through P4 is a circular place whose bound function independently produces true or false. T_out_P1 forks true into P2 and P3 or terminates false. P2 and P3 publish either result to the input join before P4. The join synchronizes arrivals, not Boolean truth; P4 produces its own Boolean and either outcome reaches final termination. All fifteen model nodes and arcs are represented. Monitor observes outside the token path.')
draw,drawn=arc_drawer(d,arcs)
d.group(20,20,1080,520,'Petri-net execution · Boolean functionality at every place')
positions={'P1':(160,260),'P2':(500,145),'P3':(500,390),'P4':(850,260)}
for place,(x,y) in positions.items():
    tin,tout='T_in_'+place,'T_out_'+place
    assert nodes[place]['type']=='PLACE'
    join=nodes[tin]['node_type']=='JoinNode'
    d.group(x-115,y-60,230,125,'Function: true / false')
    d.place(x,y,place,token=place=='P1')
    d.transition(x-80,y,tin,join=join)
    d.transition(x+80,y,tout)
    draw(tin,place,f'M {x-(66 if join else 72)} {y} L {x-27} {y}')
    draw(place,tout,f'M {x+27} {y} L {x+72} {y}')
d.box(35,90,235,70,'Generator',['Workflow tokens'],'endpoint',identity='EVENT_GENERATOR')
d.transition(160,445,'T_in_Terminate',terminal=True)
d.text(160,518,'P1 false: terminate',15,True)
d.transition(1030,410,'T_in_Model_Terminate',terminal=True)
d.text(1030,483,'Terminate',15,True)
publications={
    ('EVENT_GENERATOR','T_in_P1'):'M 160 160 L 40 185 L 40 260 L 72 260',
    ('T_out_P1','T_in_P2'):'M 248 260 L 315 260 L 315 145 L 412 145',
    ('T_out_P1','T_in_P3'):'M 248 260 L 315 260 L 315 390 L 412 390',
    ('T_out_P1','T_in_Terminate'):'M 240 284 L 240 365 L 160 365 L 160 421',
    ('T_out_P2','T_in_P4'):'M 588 145 L 670 145 L 670 260 L 756 260',
    ('T_out_P3','T_in_P4'):'M 588 390 L 670 390 L 670 260 L 756 260',
    ('T_out_P4','T_in_Model_Terminate'):'M 938 260 L 1030 260 L 1030 386',
}
for (source,target),path in publications.items(): draw(source,target,path,True)
assert drawn==set(arcs)
assert nodes['T_in_P4']['node_type']=='JoinNode'
assert all(arcs['T_out_P1',target]['decision_value']=='true' for target in ['T_in_P2','T_in_P3'])
assert arcs['T_out_P1','T_in_Terminate']['decision_value']=='false'
assert all(not arcs[source,target].get('decision_value') for source,target in publications if source not in ['EVENT_GENERATOR','T_out_P1'])
d.text(322,249,'true: fork',14,anchor='start')
d.text(253,349,'false',14,anchor='start')
d.text(647,124,'either result',14)
d.text(647,424,'either result',14)
d.text(850,362,['Join waits for P2 + P3 arrivals','P4 computes its own result'],14)
d.text(988,244,'either result',14)
d.box(350,575,420,70,'Monitor · observed execution',['Queueing · execution · joins · elapsed time'],'observe')
d.path('M 560 540 L 560 575','observe')
d.text(577,563,'collected observations',14,anchor='start',colour=TEAL)
d.text(560,675,'Circle: place · Bar: transition · AND: input arrivals, not Boolean AND · Dashed blue: publication',14)
d.text(560,694,'Each place supplies functionality; the generic execution fabric supplies coordination.',14)
d.save('petrinet-fork-join.svg')
# Executable RPSO Petri-net notation: explicit local triples, publication links.
# All model nodes and arcs retain their JSON identities; coordinates are only layout.
model_path=ROOT.parent/'btsn.common/ProcessDefinitionFolder/petrinet/Workflow/P1_to_P6_Double_Join_Workflow.json'
model=json.loads(model_path.read_text())
nodes={node['id']:node for node in model['elements']}
arcs={(arc['source'],arc['target']):arc for arc in model['arrows']}
d=Diagram(1120,830,'Petri-net model executed by the RPSO fabric','Six circular places have explicit input and output transition bars. T_out_P1 forks true to P2, P3 and P5 or routes false to T_in_Terminate. T_in_P4 joins P2/P3; T_in_P6 joins P4/P5; T_out_P6 terminates. Each local transition-place-transition unit is implemented by the generic host with a bound token operation at its place. Dashed connections are publication channels in RPSO notation. The dot is an illustrative model token; Monitor observes outside the model.')
d.group(20,20,1080,630,'Executable Petri-net model · T_in → P → T_out at every place')
positions={'P1':(145,330),'P2':(415,130),'P3':(415,330),'P4':(695,230),'P5':(415,550),'P6':(975,420)}
titles={'P1':'Boolean token','P2':'Branch 2','P3':'Branch 1','P4':'First input join','P5':'Side branch','P6':'Second input join'}
drawn_arcs=set()
def model_arc(source,target,path,publication=False):
    assert (source,target) in arcs, f'Unknown model arc: {source} → {target}'
    drawn_arcs.add((source,target))
    d.path(path,'publication' if publication else 'flow',source,target)

for place,(x,y) in positions.items():
    tin,tout='T_in_'+place,'T_out_'+place
    assert nodes[place]['type']=='PLACE'
    assert nodes[tin]['transition_type']=='T_in' and nodes[tout]['transition_type']=='T_out'
    d.group(x-115,y-60,230,125,titles[place])
    d.place(x,y,place,token=place=='P1')
    d.transition(x-80,y,tin,join=nodes[tin]['node_type']=='JoinNode')
    d.transition(x+80,y,tout,terminal=nodes[tout]['node_type']=='TerminateNode')
    model_arc(tin,place,f'M {x-(66 if nodes[tin]["node_type"]=="JoinNode" else 72)} {y} L {x-27} {y}')
    model_arc(place,tout,f'M {x+27} {y} L {x+72} {y}')

d.box(35,110,220,75,'Generator',['EVENT_GENERATOR'],'endpoint',identity='EVENT_GENERATOR')
d.transition(145,550,'T_in_Terminate',terminal=True)
d.text(145,620,'Terminate at P1',15,True)
publications={
    ('EVENT_GENERATOR','T_in_P1'):'M 145 185 L 42 232 L 42 330 L 57 330',
    ('T_out_P1','T_in_P2'):'M 233 330 L 275 330 L 275 130 L 327 130',
    ('T_out_P1','T_in_P3'):'M 233 330 L 327 330',
    ('T_out_P1','T_in_P5'):'M 233 330 L 275 330 L 275 550 L 327 550',
    ('T_out_P1','T_in_Terminate'):'M 225 354 L 225 450 L 145 450 L 145 526',
    ('T_out_P2','T_in_P4'):'M 503 130 L 555 130 L 555 230 L 601 230',
    ('T_out_P3','T_in_P4'):'M 503 330 L 555 330 L 555 230 L 601 230',
    ('T_out_P4','T_in_P6'):'M 783 230 L 835 230 L 835 420 L 881 420',
    ('T_out_P5','T_in_P6'):'M 503 550 L 835 550 L 835 420 L 881 420',
}
for (source,target),path in publications.items(): model_arc(source,target,path,True)
assert drawn_arcs==set(arcs), 'Diagram must include every configured model arc'
assert nodes['T_in_P4']['node_type']==nodes['T_in_P6']['node_type']=='JoinNode'
assert all(arcs['T_out_P1',target]['decision_value']=='true' for target in ['T_in_P2','T_in_P3','T_in_P5'])
assert arcs['T_out_P1','T_in_Terminate']['decision_value']=='false'
d.text(300,242,'true: three-way fork',13,anchor='start')
d.text(235,430,'false',13,anchor='start')
d.text(695,326,'Wait for both P2 + P3 inputs',14)
d.text(975,515,'Wait for both P4 + P5 inputs',14)
d.text(975,539,'T_out_P6 terminates',14,True)
d.box(360,700,400,70,'Monitor · observed execution',['Queueing · execution · joins · elapsed time'],'observe')
d.path('M 560 650 L 560 700','observe')
d.text(577,681,'collected host observations',14,anchor='start',colour=TEAL)
d.text(560,798,'Circle: place · Bar: transition · AND: input join · Dot: illustrative token · Dashed blue: publication',14)
d.text(560,820,'Every local triple is supported by the generic fabric; the bound operation gives its place meaning.',14)
d.save('petrinet-double-join.svg')
print('Generated nine editable SVG diagrams.')
