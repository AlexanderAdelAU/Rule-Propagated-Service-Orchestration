#!/usr/bin/env python3
"""Regenerate the README's editable SVG schematics; Python standard library only.

This is documentation tooling, independent of the Ant runtime build.
Coordinates are presentation choices; workflow names/guards come from the JSON
models named in images/README.md. Render and inspect after changing this file.
"""
from pathlib import Path
from html import escape

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
    def box(self, x,y,w,h,title,lines=(),kind='control'):
        fills={'control':'#edf3fa','business':'#edf7f0','config':'#f3eff8','endpoint':'#f5f6f8','observe':'#edf7f5'}
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fills[kind]}" stroke="#60738a" stroke-width="1.5"/>')
        self.text(x+w/2,y+29,title,19,True)
        self.text(x+w/2,y+55,lines,15)
    def path(self, d, kind='flow'):
        colour={'flow':BLUE,'rule':PURPLE,'observe':TEAL,'ack':'#65758b'}[kind]
        dash={'flow':'','rule':' stroke-dasharray="8 5"','observe':' stroke-dasharray="3 5"','ack':' stroke-dasharray="4 4"'}[kind]
        self.parts.append(f'<path d="{d}" fill="none" stroke="{colour}" stroke-width="2"{dash} marker-end="url(#{kind})"/>')
    def group(self,x,y,w,h,label):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="12" fill="#fafbfc" stroke="#98a7b6" stroke-width="1.5"/>')
        self.text(x+18,y+27,label,17,True,'start')
    def save(self,name):
        (ROOT/name).write_text('\n'.join(self.parts)+ '\n</svg>\n')

# Responsibility boundaries and the current local business-service call.
d=Diagram(1120,690,'RPSO responsibility boundaries','Process and deployment definitions install local rules in a generic orchestration host. The host invokes a separately packaged business JAR in-process, routes token publications to other hosts, and supplies collected observations to Monitor outside the business path.')
d.group(235,145,650,370,'Generic numbered host · P1–P6')
d.box(355,20,410,90,'Process and deployment definitions',['Topology · contracts · version · bindings'],'config')
d.box(405,195,310,70,'Installed local RuleBase',kind='config')
d.box(265,300,165,90,'T_in',['Buffer / synchronize'])
d.box(475,300,175,90,'ServiceThread',['Validate / invoke'])
d.box(695,300,160,90,'T_out',['Route / publish'])
d.box(450,440,255,65,'Business-service JAR',['Domain objects and decisions'],'business')
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
print('Generated five editable SVG diagrams.')
