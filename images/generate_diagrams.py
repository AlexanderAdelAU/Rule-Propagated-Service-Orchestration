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
d=Diagram(1120,505,'Financial loan-application workflow','Submitted applications reach Validation on P1. Valid results fork to Credit Check on P2 and Fraud Check on P3. Underwriting on P4 joins both named results. Approved and conditional outcomes proceed to Decision on P5; invalid and declined applications terminate early. Monitor is not a business activity.')
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

# Tutorial: real logical capability, loop and direct termination.
d=Diagram(1120,440,'P1 tutorial loop and business termination','The tutorial generator sends tokens to T_in on P1, where StochasticEntryTokenService returns a true or false result. True terminates; false returns to the same input. Collected observations go to Monitor outside the business flow.')
d.group(215,40,655,255,'P1 generic host · local execution roles')
d.box(20,150,155,85,'Generator',['Workflow tokens'],'endpoint')
d.box(245,150,145,85,'T_in',['Receive / buffer'])
d.box(430,150,240,85,'Place P1',['StochasticEntryTokenService'],'business')
d.box(715,150,125,85,'T_out',['Route'])
d.box(940,150,160,85,'Terminate',['true outcome'],'endpoint')
d.box(380,340,370,75,'Monitor',['Collected execution observations'],'observe')
d.path('M 175 192 L 245 192')
d.path('M 390 192 L 430 192')
d.path('M 670 192 L 715 192')
d.path('M 840 192 L 940 192')
d.text(890,175,'true',15)
d.path('M 777 150 L 777 103 L 317 103 L 317 150')
d.text(550,93,'false: repeat the activity',15)
d.path('M 550 295 L 550 340','observe')
d.text(567,324,'collector',14,anchor='start',colour=TEAL)
d.save('p1-tutorial.svg')
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
print('Generated six editable SVG diagrams.')
